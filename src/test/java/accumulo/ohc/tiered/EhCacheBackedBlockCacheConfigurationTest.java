package accumulo.ohc.tiered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.spi.cache.CacheType;
import org.junit.jupiter.api.Test;

class EhCacheBackedBlockCacheConfigurationTest {

  @Test
  void parsesTierPropertiesAndCacheMetadata() {
    TestConfiguration source = new TestConfiguration().withBlockSize(8192).withCache(CacheType.DATA,
        4096, Map.of("on-heap.size", "1024", "off-heap.size", "2048", "disk.size", "4096",
            "disk.dir", "/tmp/tiered-cache", "logInterval", "3"));

    EhCacheBackedBlockCacheConfiguration config =
        new EhCacheBackedBlockCacheConfiguration(source, CacheType.DATA);

    assertEquals(Map.of("size", "1024"), config.getOnHeapProps());
    assertEquals(Map.of("size", "2048"), config.getOffHeapProps());
    assertEquals(Map.of("size", "4096", "dir", "/tmp/tiered-cache"), config.getDiskProps());
    assertEquals(4096, config.getMaxSize());
    assertEquals(8192, config.getBlockSize());
    assertEquals(CacheType.DATA, config.getType());
    assertEquals(3000, config.getLogInterval(TimeUnit.MILLISECONDS));
    assertThrows(UnsupportedOperationException.class, () -> config.getDiskProps().put("size", "1"));
  }

  @Test
  void absentLogIntervalRemainsUnset() {
    EhCacheBackedBlockCacheConfiguration config = new EhCacheBackedBlockCacheConfiguration(
        new TestConfiguration().withCache(CacheType.INDEX, 0, Map.of()), CacheType.INDEX);

    assertTrue(config.getOnHeapProps().isEmpty());
    assertTrue(config.getOffHeapProps().isEmpty());
    assertTrue(config.getDiskProps().isEmpty());
    assertNull(config.getLogInterval(TimeUnit.SECONDS));
  }
}
