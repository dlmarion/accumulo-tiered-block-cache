package accumulo.ohc.tiered;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.apache.accumulo.core.spi.cache.BlockCacheManager.Configuration;
import org.apache.accumulo.core.spi.cache.CacheType;

final class TestConfiguration implements Configuration {

  private final Map<CacheType,Long> maxSizes = new EnumMap<>(CacheType.class);
  private final Map<CacheType,Map<String,String>> properties = new EnumMap<>(CacheType.class);
  private long blockSize = 4096;

  TestConfiguration withCache(CacheType type, long maxSize, Map<String,String> props) {
    maxSizes.put(type, maxSize);
    properties.put(type, new HashMap<>(props));
    return this;
  }

  TestConfiguration withBlockSize(long size) {
    blockSize = size;
    return this;
  }

  @Override
  public long getMaxSize(CacheType type) {
    return maxSizes.getOrDefault(type, 0L);
  }

  @Override
  public long getBlockSize() {
    return blockSize;
  }

  @Override
  public Map<String,String> getProperties(String prefix, CacheType type) {
    return properties.getOrDefault(type, Map.of());
  }
}
