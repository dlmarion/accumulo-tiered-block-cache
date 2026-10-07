package accumulo.ohc.tiered;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.apache.accumulo.core.spi.cache.BlockCache.Loader;
import org.apache.accumulo.core.spi.cache.CacheEntry;
import org.apache.accumulo.core.spi.cache.CacheEntry.Weighable;
import org.apache.accumulo.core.spi.cache.CacheType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EhCacheBackedBlockCacheTest {

  private static final int HEAP_SIZE = 8192;

  @TempDir
  Path tempDir;

  @Test
  void cachesAndRetrievesBlocksAndTracksStats() {
    EhCacheBackedBlockCache cache = newCache();
    try {
      byte[] buffer = {1, 2, 3};
      long requestsBefore = cache.getStats().requestCount();
      long hitsBefore = cache.getStats().hitCount();

      CacheEntry inserted = cache.cacheBlock("block", buffer);
      assertSame(buffer, inserted.getBuffer());
      assertNull(cache.getBlock("missing"));
      assertArrayEquals(buffer, cache.getBlock("block").getBuffer());
      assertEquals(HEAP_SIZE, cache.getMaxHeapSize());
      assertEquals(HEAP_SIZE, cache.getMaxSize());
      assertEquals(requestsBefore + 2, cache.getStats().requestCount());
      assertEquals(hitsBefore + 1, cache.getStats().hitCount());

      assertThrows(NullPointerException.class, () -> cache.cacheBlock(null, buffer));
      assertThrows(NullPointerException.class, () -> cache.cacheBlock("null-buffer", null));
    } finally {
      cache.close();
    }
  }

  @Test
  void cacheEntryCreatesAndUpdatesItsIndex() {
    EhCacheBackedBlockCache cache = newCache();
    try {
      CacheEntry entry = cache.cacheBlock("indexed", new byte[] {4, 5});
      MutableWeighable index = new MutableWeighable(10);
      AtomicInteger supplierCalls = new AtomicInteger();

      assertSame(index, entry.getIndex(() -> {
        supplierCalls.incrementAndGet();
        return index;
      }));
      entry.indexWeightChanged();
      index.weight = 20;
      entry.indexWeightChanged();
      assertSame(index, entry.getIndex(() -> {
        supplierCalls.incrementAndGet();
        return new MutableWeighable(30);
      }));
      assertEquals(1, supplierCalls.get());
      assertArrayEquals(new byte[] {4, 5}, cache.getBlock("indexed").getBuffer());
    } finally {
      cache.close();
    }
  }

  @Test
  void loaderResultIsCachedAndSubsequentLoadsUseTheCache() {
    EhCacheBackedBlockCache cache = newCache();
    try {
      byte[] loaded = {8, 9};
      AtomicInteger calls = new AtomicInteger();
      Loader loader = loader(Map.of(), (maxSize, deps) -> {
        calls.incrementAndGet();
        assertEquals(HEAP_SIZE, maxSize);
        assertEquals(Map.of(), deps);
        return loaded;
      });

      assertArrayEquals(loaded, cache.getBlock("loaded", loader).getBuffer());
      assertArrayEquals(loaded, cache.getBlock("loaded", loader).getBuffer());
      assertEquals(1, calls.get());
    } finally {
      cache.close();
    }
  }

  @Test
  void nullLoaderResultIsNotCached() {
    EhCacheBackedBlockCache cache = newCache();
    try {
      AtomicInteger calls = new AtomicInteger();
      Loader loader = loader(Map.of(), (maxSize, deps) -> {
        calls.incrementAndGet();
        return null;
      });

      assertNull(cache.getBlock("empty", loader));
      assertNull(cache.getBlock("empty", loader));
      assertEquals(2, calls.get());
    } finally {
      cache.close();
    }
  }

  @Test
  void resolvesAndPassesAllDependenciesToLoader() {
    EhCacheBackedBlockCache cache = newCache();
    try {
      byte[] first = {1};
      byte[] second = {2};
      byte[] result = {3};
      Loader firstLoader = loader(Map.of(), (maxSize, deps) -> first);
      Loader secondLoader = loader(Map.of(), (maxSize, deps) -> second);
      Map<String,Loader> dependencies = Map.of("first", firstLoader, "second", secondLoader);
      AtomicInteger calls = new AtomicInteger();
      Loader parentLoader = loader(dependencies, (maxSize, deps) -> {
        calls.incrementAndGet();
        assertEquals(Map.of("first", first, "second", second), deps);
        return result;
      });

      CacheEntry loaded = cache.getBlock("parent", parentLoader);

      assertNotNull(loaded);
      assertArrayEquals(result, loaded.getBuffer());
      assertArrayEquals(first, cache.getBlock("first").getBuffer());
      assertArrayEquals(second, cache.getBlock("second").getBuffer());
      assertEquals(1, calls.get());
    } finally {
      cache.close();
    }
  }

  @Test
  void missingDependencyPreventsParentLoad() {
    EhCacheBackedBlockCache cache = newCache();
    try {
      AtomicInteger parentCalls = new AtomicInteger();
      Loader missingDependency = loader(Map.of(), (maxSize, deps) -> null);
      Loader parent = loader(Map.of("missing", missingDependency), (maxSize, deps) -> {
        parentCalls.incrementAndGet();
        return new byte[] {1};
      });

      assertNull(cache.getBlock("parent", parent));
      assertEquals(0, parentCalls.get());
    } finally {
      cache.close();
    }
  }

  @Test
  void rejectsTierSizesThatAreNotIncreasing() {
    TestConfiguration source = new TestConfiguration().withCache(CacheType.DATA, 4096,
        Map.of("on-heap.size", "2048", "off-heap.size", "1024"));
    EhCacheBackedBlockCacheConfiguration config =
        new EhCacheBackedBlockCacheConfiguration(source, CacheType.DATA);

    assertThrows(IllegalStateException.class,
        () -> new EhCacheBackedBlockCache(config, CacheType.DATA));
  }

  @Test
  void createsAndUsesDiskTierFromDiskProperties() throws IOException {
    long heapSize = 1024 * 1024;
    long diskSize = 16 * 1024 * 1024;
    TestConfiguration source =
        new TestConfiguration().withCache(CacheType.DATA, heapSize + diskSize,
            Map.of("on-heap.size", Long.toString(heapSize), "disk.size", Long.toString(diskSize),
                "disk.dir", tempDir.toString(), "disk.segments", "4", "disk.threads", "2"));
    EhCacheBackedBlockCache cache = new EhCacheBackedBlockCache(
        new EhCacheBackedBlockCacheConfiguration(source, CacheType.DATA), CacheType.DATA);
    try {
      byte[] data = {6, 7};
      cache.cacheBlock("disk-backed", data);
      assertArrayEquals(data, cache.getBlock("disk-backed").getBuffer());
      assertEquals(heapSize + diskSize, cache.getMaxSize());
      List<Path> files = Files.list(tempDir).collect(Collectors.toList());
      assertNotNull(files);
      assertTrue(files.size() > 0);
      files.forEach(f -> {
        // Don't check the .lock file
        if (!f.toFile().isHidden()) {
          assertTrue(f.toFile().length() > 0, f + " has zero length");
        }
      });
    } finally {
      cache.close();
    }
  }

  @Test
  void createsAndUsesOffHeapTier() {
    long offHeapSize = 16 * 1024 * 1024;
    TestConfiguration source = new TestConfiguration().withCache(CacheType.INDEX, offHeapSize,
        Map.of("off-heap.size", Long.toString(offHeapSize)));
    EhCacheBackedBlockCache cache = new EhCacheBackedBlockCache(
        new EhCacheBackedBlockCacheConfiguration(source, CacheType.INDEX), CacheType.INDEX);
    try {
      byte[] data = {10, 11};
      cache.cacheBlock("off-heap-backed", data);
      assertArrayEquals(data, cache.getBlock("off-heap-backed").getBuffer());
      assertEquals(0, cache.getMaxHeapSize());
      assertEquals(offHeapSize, cache.getMaxSize());
    } finally {
      cache.close();
    }
  }

  private EhCacheBackedBlockCache newCache() {
    TestConfiguration source = new TestConfiguration().withCache(CacheType.DATA, HEAP_SIZE,
        Map.of("on-heap.size", Integer.toString(HEAP_SIZE)));
    return new EhCacheBackedBlockCache(
        new EhCacheBackedBlockCacheConfiguration(source, CacheType.DATA), CacheType.DATA);
  }

  private static Loader loader(Map<String,Loader> dependencies,
      BiFunction<Integer,Map<String,byte[]>,byte[]> loadFunction) {
    return new Loader() {
      @Override
      public Map<String,Loader> getDependencies() {
        return dependencies;
      }

      @Override
      public byte[] load(int maxSize, Map<String,byte[]> resolvedDependencies) {
        return loadFunction.apply(maxSize, resolvedDependencies);
      }
    };
  }

  private static class MutableWeighable implements Weighable {
    private int weight;

    MutableWeighable(int weight) {
      this.weight = weight;
    }

    @Override
    public int weight() {
      return weight;
    }
  }
}
