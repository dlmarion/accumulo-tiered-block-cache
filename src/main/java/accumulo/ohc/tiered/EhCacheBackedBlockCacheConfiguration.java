package accumulo.ohc.tiered;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.spi.cache.BlockCacheManager.Configuration;
import org.apache.accumulo.core.spi.cache.CacheType;

public class EhCacheBackedBlockCacheConfiguration {

  public static final String PROPERTY_PREFIX = "tiered";

  public static final String ON_HEAP_PREFIX = "on-heap.";
  public static final String OFF_HEAP_PREFIX = "off-heap.";
  public static final String DISK_PREFIX = "disk.";
  public static final String LOG_INTERVAL_PROP = "logInterval";

  private final Map<String,String> onHeapProps;
  private final Map<String,String> offHeapProps;
  private final Map<String,String> diskProps;
  private final long maxSize;
  private final long blockSize;
  private final CacheType type;
  private final String logInterval;

  public EhCacheBackedBlockCacheConfiguration(Configuration conf, CacheType type) {
    final Map<String,String> allProps = conf.getProperties(PROPERTY_PREFIX, type);

    final Map<String,String> on = new HashMap<>();
    final Map<String,String> off = new HashMap<>();
    final Map<String,String> disk = new HashMap<>();

    allProps.forEach((k, v) -> {
      if (k.startsWith(ON_HEAP_PREFIX)) {
        on.put(k.substring(ON_HEAP_PREFIX.length()), v);
      } else if (k.startsWith(OFF_HEAP_PREFIX)) {
        off.put(k.substring(OFF_HEAP_PREFIX.length()), v);
      } else if (k.startsWith(DISK_PREFIX)) {
        disk.put(k.substring(OFF_HEAP_PREFIX.length()), v);
      }
    });

    this.onHeapProps = Collections.unmodifiableMap(on);
    this.offHeapProps = Collections.unmodifiableMap(off);
    this.diskProps = Collections.unmodifiableMap(disk);
    this.maxSize = conf.getMaxSize(type);
    this.blockSize = conf.getBlockSize();
    this.logInterval = allProps.get(LOG_INTERVAL_PROP);
    this.type = type;

  }

  public Map<String,String> getOnHeapProps() {
    return onHeapProps;
  }

  public Map<String,String> getOffHeapProps() {
    return offHeapProps;
  }

  public Map<String,String> getDiskProps() {
    return diskProps;
  }

  public long getMaxSize() {
    return maxSize;
  }

  public long getBlockSize() {
    return blockSize;
  }

  public CacheType getType() {
    return type;
  }

  public Long getLogInterval(TimeUnit unit) {
    return logInterval == null ? null : unit.convert(Long.valueOf(logInterval), TimeUnit.SECONDS);
  }

}
