package accumulo.ohc.tiered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.accumulo.core.spi.cache.CacheEntry.Weighable;
import org.junit.jupiter.api.Test;

class BlockTest {

  @Test
  void preservesBufferAndCreatesIndexOnlyOnce() {
    byte[] buffer = new byte[256];
    Block block = new Block(buffer);
    AtomicInteger supplierCalls = new AtomicInteger();
    Weighable index = () -> 10;

    assertSame(buffer, block.getBuffer());
    assertSame(index, block.getIndex(() -> {
      supplierCalls.incrementAndGet();
      return index;
    }));
    assertSame(index, block.getIndex(() -> {
      supplierCalls.incrementAndGet();
      return () -> 99;
    }));
    assertEquals(1, supplierCalls.get());
  }

  @Test
  void reportsIndexWeightChangesOnce() {
    Block block = new Block(new byte[100]);
    MutableWeighable index = new MutableWeighable(7);
    int initialWeight = block.weight();
    assertFalse(block.indexWeightChanged());
    block.getIndex(() -> index);

    assertTrue(block.indexWeightChanged());
    assertEquals(initialWeight + 6, block.weight());
    assertFalse(block.indexWeightChanged());

    index.weight = 12;
    assertTrue(block.indexWeightChanged());
    assertEquals(initialWeight + 11, block.weight());
    assertFalse(block.indexWeightChanged());
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
