package accumulo.ohc.tiered;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Supplier;

import org.apache.accumulo.core.spi.cache.BlockCache;
import org.apache.accumulo.core.spi.cache.CacheEntry;
import org.apache.accumulo.core.spi.cache.CacheType;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.PersistentCacheManager;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.core.internal.statistics.DefaultStatisticsService;
import org.ehcache.core.spi.service.StatisticsService;
import org.ehcache.core.statistics.CacheStatistics;
import org.ehcache.impl.config.store.disk.OffHeapDiskStoreConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

public class EhCacheBackedBlockCache implements BlockCache {

  private class TlfuCacheEntry implements CacheEntry {

    private final String cacheKey;
    private final Block block;

    TlfuCacheEntry(String k, Block b) {
      this.cacheKey = k;
      this.block = b;
    }

    @Override
    public byte[] getBuffer() {
      return block.getBuffer();
    }

    @Override
    public <T extends Weighable> T getIndex(Supplier<T> supplier) {
      return block.getIndex(supplier);
    }

    @Override
    public void indexWeightChanged() {
      if (block.indexWeightChanged()) {
        // update weight
        cache.put(cacheKey, block.getBuffer());
      }
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(EhCacheBackedBlockCache.class);

  private static final String TIER_SIZE = "size";
  private static final String DISK_TIER_CACHE_DIR = "dir";
  private static final String DISK_SEGMENTS = "segments";
  private static final String DISK_THREADS = "threads";

  private final long onHeapSize;
  private final long offHeapSize;
  private final long diskSize;
  private final PersistentCacheManager cacheManager;
  private final Cache<String,byte[]> cache;
  private final CacheStatistics cacheStats;

  EhCacheBackedBlockCache(final EhCacheBackedBlockCacheConfiguration config, CacheType type) {

    final String cacheAlias = type.name() + " Block Cache";
    onHeapSize = Long.parseLong(config.getOnHeapProps().getOrDefault(TIER_SIZE, "0"));
    offHeapSize = Long.parseLong(config.getOffHeapProps().getOrDefault(TIER_SIZE, "0"));
    diskSize = Long.parseLong(config.getDiskProps().getOrDefault(TIER_SIZE, "0"));

    // Valid Configurations:
    // Heap
    // OffHeap
    // Disk
    // Heap + OffHeap
    // Heap + Disk
    // Heap + OffHeap + Disk

    if (offHeapSize > 0L) {
      Preconditions.checkState(offHeapSize > onHeapSize);
      if (diskSize > 0L) {
        Preconditions.checkState(diskSize > offHeapSize);
      }
    } else if (diskSize > 0L) {
      Preconditions.checkState(diskSize > onHeapSize);
    }

    ResourcePoolsBuilder resources = ResourcePoolsBuilder.newResourcePoolsBuilder();
    config.getOffHeapProps();
    if (onHeapSize > 0L) {
      resources.heap(onHeapSize, MemoryUnit.B);
    }
    if (offHeapSize > 0L) {
      resources.offheap(offHeapSize, MemoryUnit.B);
    }
    int segments = 0;
    int threads = 0;
    if (diskSize > 0L) {
      resources.disk(diskSize, MemoryUnit.B);
      segments = Integer.parseInt(config.getDiskProps().getOrDefault(DISK_SEGMENTS, "16"));
      threads = Integer.parseInt(config.getDiskProps().getOrDefault(DISK_THREADS, "1"));
    }

    CacheConfigurationBuilder<String,byte[]> ccb = CacheConfigurationBuilder
        .newCacheConfigurationBuilder(String.class, byte[].class, resources.build());

    if (segments != 16 || threads != 1) {
      ccb.withService(new OffHeapDiskStoreConfiguration(null, threads, segments));
    }

    CacheConfiguration<String,byte[]> cacheConfiguration = ccb.build();

    CacheManagerBuilder<CacheManager> cmb = CacheManagerBuilder.newCacheManagerBuilder();

    if (diskSize > 0L) {
      String dir = config.getDiskProps().getOrDefault(DISK_TIER_CACHE_DIR, null);
      Objects.requireNonNull(dir, "Cache directory must be specified for disk tier");
      cmb.with(CacheManagerBuilder.persistence(dir));
    }

    StatisticsService statisticsService = new DefaultStatisticsService();

    cacheManager = (PersistentCacheManager) cmb.withCache(cacheAlias, cacheConfiguration)
        .using(statisticsService).build(true);
    cache = cacheManager.getCache(cacheAlias, String.class, byte[].class);
    cacheStats = statisticsService.getCacheStatistics(cacheAlias);
  }

  @Override
  public CacheEntry cacheBlock(String blockName, byte[] buf) {
    Objects.requireNonNull(blockName, "Block name is null");
    Objects.requireNonNull(buf, "Block buffer is null");
    cache.put(blockName, buf);
    return new TlfuCacheEntry(blockName, new Block(buf));
  }

  @Override
  public CacheEntry getBlock(String blockName) {
    byte[] buf = cache.get(blockName);
    if (buf == null) {
      return null;
    } else {
      return new TlfuCacheEntry(blockName, new Block(buf));
    }
  }

  private Block load(String key, Loader loader, Map<String,byte[]> resolvedDeps) {

    CacheEntry entry = getBlock(key);
    if (entry == null) {
      byte[] data = loader.load((int) Math.min(Integer.MAX_VALUE, this.onHeapSize), resolvedDeps);
      if (data == null) {
        return null;
      } else {
        LOG.info("Loaded block {} from Loader", key);
        return new Block(data);
      }
    } else {
      return new Block(entry.getBuffer());
    }
  }

  private Map<String,byte[]> resolveDependencies(Map<String,Loader> deps) {
    if (deps.size() == 1) {
      Entry<String,Loader> entry = deps.entrySet().iterator().next();
      CacheEntry ce = getBlock(entry.getKey(), entry.getValue());
      if (ce == null) {
        return null;
      }
      return Collections.singletonMap(entry.getKey(), ce.getBuffer());
    } else {
      HashMap<String,byte[]> resolvedDeps = new HashMap<>();
      for (Entry<String,Loader> entry : deps.entrySet()) {
        CacheEntry ce = getBlock(entry.getKey(), entry.getValue());
        if (ce == null) {
          return null;
        }
        resolvedDeps.put(entry.getKey(), ce.getBuffer());
      }
      return resolvedDeps;
    }
  }

  @Override
  public CacheEntry getBlock(String blockName, Loader loader) {
    Map<String,Loader> deps = loader.getDependencies();
    Block block = null;
    if (deps.size() == 0) {
      block = load(blockName, loader, Collections.emptyMap());
      cache.putIfAbsent(blockName, block.getBuffer());
    } else {
      // This code path exist to handle the case where dependencies may need to be loaded. Loading
      // dependencies will access the cache. Cache load functions
      // should not access the cache.
      byte[] data = cache.get(blockName);

      if (data == null) {
        // Load dependencies outside of cache load function.
        Map<String,byte[]> resolvedDeps = resolveDependencies(deps);
        if (resolvedDeps == null) {
          return null;
        }

        // Use asMap because it will not increment stats, getIfPresent recorded a miss above. Use
        // computeIfAbsent because it is possible another thread loaded
        // the data since this thread called getIfPresent.
        block = load(blockName, loader, Collections.emptyMap());
        cache.putIfAbsent(blockName, block.getBuffer());
      }
    }
    return new TlfuCacheEntry(blockName, block);

  }

  @Override
  public long getMaxHeapSize() {
    return onHeapSize;
  }

  @Override
  public long getMaxSize() {
    return onHeapSize + offHeapSize + diskSize;
  }

  @Override
  public Stats getStats() {
    return new Stats() {

      @Override
      public long hitCount() {
        return cacheStats.getCacheHits();
      }

      @Override
      public long requestCount() {
        return cacheStats.getCacheGets();
      }

      @Override
      public long evictionCount() {
        return cacheStats.getCacheEvictions();
      }

    };
  }

  public void logStats() {
    LOG.info("Cache Stats: {}", cacheStats.getTierStatistics());
  }

}
