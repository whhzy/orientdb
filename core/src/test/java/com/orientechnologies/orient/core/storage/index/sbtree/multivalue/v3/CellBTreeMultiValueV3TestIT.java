package com.orientechnologies.orient.core.storage.index.sbtree.multivalue.v3;

import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.serialization.types.OUTF8Serializer;
import com.orientechnologies.common.types.OModifiableInteger;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.*;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperationsManager;
import com.orientechnologies.orient.core.storage.index.sbtree.multivalue.OCellBTreeMultiValue;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CellBTreeMultiValueV3TestIT {
  private CellBTreeMultiValueV3<String> multiValueTree;
  private OrientDB                      orientDB;
  private OAbstractPaginatedStorage     storage;

  private final String DB_NAME = "localMultiBTreeTest";

  @Before
  public void before() throws IOException {
    final String buildDirectory =
        System.getProperty("buildDirectory", ".") + File.separator + CellBTreeMultiValueV3TestIT.class.getSimpleName();

    final File dbDirectory = new File(buildDirectory, DB_NAME);
    OFileUtils.deleteRecursively(dbDirectory);

    final OrientDBConfig config = OrientDBConfig.builder().addConfig(OGlobalConfiguration.STORAGE_TRACK_PAGE_OPERATIONS_IN_TX, true)
        .build();
    orientDB = new OrientDB("plocal:" + buildDirectory, config);
    orientDB.create(DB_NAME, ODatabaseType.PLOCAL);

    final ODatabaseSession databaseDocumentTx = orientDB.open(DB_NAME, "admin", "admin");

    storage = (OAbstractPaginatedStorage) ((ODatabaseInternal) databaseDocumentTx).getStorage();
    multiValueTree = new CellBTreeMultiValueV3<>("multiBTree", ".sbt", ".nbt", ".mdt", storage);
    multiValueTree.create(OUTF8Serializer.INSTANCE, null, 1, null);
  }

  @After
  public void afterMethod() {
    orientDB.drop(DB_NAME);
    orientDB.close();
  }

  @Test
  public void testPutNullKey() throws Exception {
    final int itemsCount = 64_000;

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32000, value)));

    final List<ORID> result = multiValueTree.get(null);

    Assert.assertEquals(itemsCount, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new ORecordId(i % 32000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveNullKey() throws IOException {
    final int itemsCount = 69_000;

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32000, value)));

    doInRollbackLoop(0, itemsCount / 3, 100,
        (value, rollback) -> multiValueTree.remove(null, new ORecordId(3 * value % 32_000, 3 * value)));

    final List<ORID> result = multiValueTree.get(null);

    Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount / 3; i++) {
      final int val = i * 3;
      Assert.assertTrue(resultSet.contains(new ORecordId((val + 1) % 32000, (val + 1))));
      Assert.assertTrue(resultSet.contains(new ORecordId((val + 2) % 32000, (val + 2))));
    }
  }

  @Test
  public void testKeyPutRemoveSameTimeNullKey() throws IOException {
    final int itemsCount = 69_000;
    final OModifiableInteger removed = new OModifiableInteger();

    doInRollbackLoop(0, itemsCount, 100, ((value, rollback) -> {
      multiValueTree.put(null, new ORecordId(value % 32_000, value));

      if (value % 3 == 0) {
        multiValueTree.remove(null, new ORecordId(value % 32_000, value));
        if (!rollback) {
          removed.increment();
        }
      }
    }));

    final List<ORID> result = multiValueTree.get(null);

    Assert.assertEquals(itemsCount - removed.value, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount / 3; i++) {
      final int val = i * 3;
      Assert.assertTrue(resultSet.contains(new ORecordId((val + 1) % 32000, (val + 1))));
      Assert.assertTrue(resultSet.contains(new ORecordId((val + 2) % 32000, (val + 2))));
    }
  }

  @Test
  public void testKeyPutRemoveSameTimeBatchNullKey() throws IOException {
    final int itemsCount = 63_000;
    final OModifiableInteger removed = new OModifiableInteger();

    doInRollbackLoop(0, itemsCount, 100, ((value, rollback) -> {
      multiValueTree.put(null, new ORecordId(value % 32000, value));

      if (value > 0 && value % 9 == 0) {
        multiValueTree.remove(null, new ORecordId((value - 3) % 32_000, value - 3));
        multiValueTree.remove(null, new ORecordId((value - 6) % 32_000, value - 6));
        multiValueTree.remove(null, new ORecordId((value - 9) % 32_000, value - 9));

        if (!rollback) {
          removed.increment(3);
        }
      }
    }));

    final int roundedItems = ((itemsCount + 8) / 9) * 9;
    for (int n = 3; n < 10; n += 3) {
      multiValueTree.remove(null, new ORecordId((roundedItems - n) % 32_000, roundedItems - n));
      if (roundedItems - n < itemsCount) {
        removed.increment();
      }
    }

    final List<ORID> result = multiValueTree.get(null);

    Assert.assertEquals(itemsCount - removed.value, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount / 3; i++) {
      final int val = i * 3;
      Assert.assertTrue(resultSet.contains(new ORecordId((val + 1) % 32000, (val + 1))));
      Assert.assertTrue(resultSet.contains(new ORecordId((val + 2) % 32000, (val + 2))));
    }
  }

  @Test
  public void testKeyPutRemoveSliceNullKey() throws IOException {
    final int itemsCount = 64_000;

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32000, value)));

    final int start = itemsCount / 3;
    final int end = 2 * itemsCount / 3;

    doInRollbackLoop(start, end, 100, (value, rollback) -> multiValueTree.remove(null, new ORecordId(value % 32_000, value)));

    final List<ORID> result = multiValueTree.get(null);

    Assert.assertEquals(itemsCount - (end - start), result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount; i++) {
      if (i >= start && i < end) {
        Assert.assertFalse(resultSet.contains(new ORecordId(i % 32_000, i)));
      } else {
        Assert.assertTrue(resultSet.contains(new ORecordId(i % 32_000, i)));
      }
    }
  }

  @Test
  public void testKeyPutRemoveSliceAndAddBackNullKey() throws IOException {
    final int itemsCount = 64_000;

    doInRollbackLoop(0, itemsCount, 100, ((value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32000, value))));

    final int start = itemsCount / 3;
    final int end = 2 * itemsCount / 3;

    doInRollbackLoop(start, end, 100, (value, rollback) -> multiValueTree.remove(null, new ORecordId(value % 32_000, value)));
    doInRollbackLoop(start, end, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32_000, value)));

    final List<ORID> result = multiValueTree.get(null);

    Assert.assertEquals(itemsCount, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new ORecordId(i % 32_000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveSliceBeginEndNullKey() throws IOException {
    final int itemsCount = 64_000;

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32000, value)));

    final int rollbackSlice = 100;
    final int start = itemsCount / 3;
    final int end = 2 * itemsCount / 3;

    doInRollbackLoop(0, start, rollbackSlice,
        (value, rollback) -> multiValueTree.remove(null, new ORecordId(value % 32_000, value)));
    doInRollbackLoop(end, itemsCount, 100, (value, rollback) -> multiValueTree.remove(null, new ORecordId(value % 32_000, value)));

    final List<ORID> result = multiValueTree.get(null);

    Assert.assertEquals(itemsCount - (start + (itemsCount - end)), result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount; i++) {
      if (i < start || i >= end) {
        Assert.assertFalse(resultSet.contains(new ORecordId(i % 32_000, i)));
      } else {
        Assert.assertTrue(resultSet.contains(new ORecordId(i % 32_000, i)));
      }
    }
  }

  @Test
  public void testKeyPutRemoveSliceBeginEndAddBackOneNullKey() throws IOException {
    final int itemsCount = 64_000;

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32000, value)));

    final int start = itemsCount / 3;
    final int end = 2 * itemsCount / 3;

    doInRollbackLoop(0, start, 100, (value, rollback) -> multiValueTree.remove(null, new ORecordId(value % 32_000, value)));
    doInRollbackLoop(end, itemsCount, 100, (value, rollback) -> multiValueTree.remove(null, new ORecordId(value % 32_000, value)));
    doInRollbackLoop(0, start, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32_000, value)));
    doInRollbackLoop(end, itemsCount, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32_000, value)));

    final List<ORID> result = multiValueTree.get(null);

    Assert.assertEquals(itemsCount, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new ORecordId(i % 32_000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveSliceBeginEndAddBackTwoNullKey() throws IOException {
    final int itemsCount = 64_000;

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32000, value)));

    final int start = itemsCount / 3;
    final int end = 2 * itemsCount / 3;

    doInRollbackLoop(0, start, 100, (value, rollback) -> multiValueTree.remove(null, new ORecordId(value % 32_000, value)));
    doInRollbackLoop(0, start, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32_000, value)));
    doInRollbackLoop(end, itemsCount, 100, (value, rollback) -> multiValueTree.remove(null, new ORecordId(value % 32_000, value)));
    doInRollbackLoop(end, itemsCount, 100, (value, rollback) -> multiValueTree.put(null, new ORecordId(value % 32_000, value)));

    final List<ORID> result = multiValueTree.get(null);

    Assert.assertEquals(itemsCount, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new ORecordId(i % 32_000, i)));
    }
  }

  @Test
  public void testKeyPutSameKey() throws IOException {
    final int itemsCount = 1_000_000;
    final String key = "test_key";

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> multiValueTree.put(key, new ORecordId(value % 32000, value)));

    final List<ORID> result = multiValueTree.get(key);

    Assert.assertEquals(itemsCount, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new ORecordId(i % 32000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveSameKey() throws IOException {
    final int itemsCount = 256_000;
    final String key = "test_key";

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> multiValueTree.put(key, new ORecordId(value % 32000, value)));

    doInRollbackLoop(0, itemsCount / 3, 100, (value, rollback) -> {
      final int val = 3 * value;
      multiValueTree.remove(key, new ORecordId(val % 32_000, val));
    });

    final List<ORID> result = multiValueTree.get(key);

    Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount / 3; i++) {
      final int val = i * 3;
      Assert.assertTrue(resultSet.contains(new ORecordId((val + 1) % 32000, (val + 1))));
      Assert.assertTrue(resultSet.contains(new ORecordId((val + 2) % 32000, (val + 2))));
    }
  }

  @Test
  public void testKeyPutTwoSameKeys() throws Exception {
    final int itemsCount = 1_000_000;
    final String keyOne = "test_key_one";
    final String keyTwo = "test_key_two";

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> {
      multiValueTree.put(keyOne, new ORecordId(value % 32000, value));
      multiValueTree.put(keyTwo, new ORecordId(value % 32000, value));
    });

    List<ORID> result = multiValueTree.get(keyOne);

    Assert.assertEquals(itemsCount, result.size());
    Set<ORID> resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new ORecordId(i % 32000, i)));
    }

    result = multiValueTree.get(keyTwo);

    Assert.assertEquals(itemsCount, result.size());
    resultSet = new HashSet<>(result);

    for (int i = 0; i < itemsCount; i++) {
      Assert.assertTrue(resultSet.contains(new ORecordId(i % 32000, i)));
    }
  }

  @Test
  public void testKeyPutRemoveTwoSameKey() throws Exception {
    final int itemsCount = 1_000_000;
    final String keyOne = "test_key_1";
    final String keyTwo = "test_key_2";

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> {
      multiValueTree.put(keyOne, new ORecordId(value % 32000, value));
      multiValueTree.put(keyTwo, new ORecordId(value % 32000, value));
    });

    doInRollbackLoop(0, itemsCount / 3, 100, (value, rollback) -> {
      final int val = 3 * value;
      multiValueTree.remove(keyOne, new ORecordId(val % 32_000, val));
      multiValueTree.remove(keyTwo, new ORecordId(val % 32_000, val));
    });

    {
      final List<ORID> result = multiValueTree.get(keyOne);

      Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
      Set<ORID> resultSet = new HashSet<>(result);

      for (int i = 0; i < itemsCount / 3; i++) {
        final int val = i * 3;
        Assert.assertTrue(resultSet.contains(new ORecordId((val + 1) % 32000, (val + 1))));
        Assert.assertTrue(resultSet.contains(new ORecordId((val + 2) % 32000, (val + 2))));
      }
    }

    {
      final List<ORID> result = multiValueTree.get(keyTwo);

      Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
      Set<ORID> resultSet = new HashSet<>(result);

      for (int i = 0; i < itemsCount / 3; i++) {
        final int val = i * 3;
        Assert.assertTrue(resultSet.contains(new ORecordId((val + 1) % 32000, (val + 1))));
        Assert.assertTrue(resultSet.contains(new ORecordId((val + 2) % 32000, (val + 2))));
      }
    }
  }

  @Test
  public void testKeyPutTenSameKeys() throws Exception {
    final int itemsCount = 1_000_000;

    final String[] keys = new String[10];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + i;
    }

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> {
      for (String key : keys) {
        multiValueTree.put(key, new ORecordId(value % 32000, value));
      }
    });

    for (String key : keys) {
      List<ORID> result = multiValueTree.get(key);

      Assert.assertEquals(itemsCount, result.size());
      Set<ORID> resultSet = new HashSet<>(result);
      Assert.assertEquals(itemsCount, resultSet.size());

      for (int i = 0; i < itemsCount; i++) {
        Assert.assertTrue(resultSet.contains(new ORecordId(i % 32000, i)));
      }
    }
  }

  @Test
  public void testKeyPutTenSameKeysReverse() throws Exception {
    final int itemsCount = 1_000_000;

    final String[] keys = new String[10];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + (9 - i);
    }

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> {
      for (String key : keys) {
        multiValueTree.put(key, new ORecordId(value % 32000, value));
      }
    });

    for (String key : keys) {
      List<ORID> result = multiValueTree.get(key);

      Assert.assertEquals(itemsCount, result.size());
      Set<ORID> resultSet = new HashSet<>(result);

      for (int i = 0; i < itemsCount; i++) {
        Assert.assertTrue(resultSet.contains(new ORecordId(i % 32000, i)));
      }
    }
  }

  @Test
  public void testKeyPutRemoveTenSameKeys() throws Exception {
    final int itemsCount = 100_000;

    final String[] keys = new String[10];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + i;
    }

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> {
      for (String key : keys) {
        multiValueTree.put(key, new ORecordId(value % 32000, value));
      }
    });

    doInRollbackLoop(0, itemsCount / 3, 100, (value, rollback) -> {
      final int val = 3 * value;

      for (String key : keys) {
        multiValueTree.remove(key, new ORecordId(val % 32_000, val));
      }
    });

    for (String key : keys) {
      final List<ORID> result = multiValueTree.get(key);

      Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
      Set<ORID> resultSet = new HashSet<>(result);

      for (int i = 0; i < itemsCount / 3; i++) {
        final int val = i * 3;
        Assert.assertTrue(resultSet.contains(new ORecordId((val + 1) % 32000, (val + 1))));
        Assert.assertTrue(resultSet.contains(new ORecordId((val + 2) % 32000, (val + 2))));
      }
    }
  }

  @Test
  public void testKeyPutThousandSameKeys() throws Exception {
    final int itemsCount = 20_000;

    final String[] keys = new String[1_000];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + i;
    }

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> {
      for (String key : keys) {
        multiValueTree.put(key, new ORecordId(value % 32000, value));
      }
      if (!rollback && value % 100 == 0) {
        System.out.printf("%d entries were inserted out of %d %n", value, itemsCount);
      }
    });

    for (String key : keys) {
      List<ORID> result = multiValueTree.get(key);

      Assert.assertEquals(itemsCount, result.size());
      Set<ORID> resultSet = new HashSet<>(result);

      for (int i = 0; i < itemsCount; i++) {
        Assert.assertTrue(resultSet.contains(new ORecordId(i % 32000, i)));
      }
    }
  }

  @Test
  public void testKeyPutRemoveThousandSameKeys() throws Exception {
    final int itemsCount = 4_000;

    final String[] keys = new String[1000];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = "test_key_" + i;
    }

    doInRollbackLoop(0, itemsCount, 100, (value, rollback) -> {
      for (String key : keys) {
        multiValueTree.put(key, new ORecordId(value % 32000, value));
      }
    });

    doInRollbackLoop(0, itemsCount / 3, 100, (value, rollback) -> {
      final int val = 3 * value;

      for (String key : keys) {
        multiValueTree.remove(key, new ORecordId(val % 32_000, val));
      }
    });

    for (String key : keys) {
      final List<ORID> result = multiValueTree.get(key);

      Assert.assertEquals(itemsCount - itemsCount / 3, result.size());
      Set<ORID> resultSet = new HashSet<>(result);

      for (int i = 0; i < itemsCount / 3; i++) {
        final int val = i * 3;
        Assert.assertTrue(resultSet.contains(new ORecordId((val + 1) % 32000, (val + 1))));
        Assert.assertTrue(resultSet.contains(new ORecordId((val + 2) % 32000, (val + 2))));
      }
    }
  }

  @Test
  public void testKeyPut() throws Exception {
    final int keysCount = 1_000_000;

    final String[] lastKey = new String[1];

    doInRollbackLoop(0, keysCount, 100, (value, rollback) -> {
      final String key = Integer.toString(value);
      multiValueTree.put(key, new ORecordId(value % 32000, value));

      if (!rollback) {
        if (value % 100_000 == 0) {
          System.out.printf("%d items loaded out of %d%n", value, keysCount);
        }

        if (lastKey[0] == null) {
          lastKey[0] = key;
        } else if (key.compareTo(lastKey[0]) > 0) {
          lastKey[0] = key;
        }

        Assert.assertEquals("0", multiValueTree.firstKey());
        Assert.assertEquals(lastKey[0], multiValueTree.lastKey());
      }
    });

    for (int i = 0; i < keysCount; i++) {
      final List<ORID> result = multiValueTree.get(Integer.toString(i));
      Assert.assertEquals(1, result.size());

      Assert.assertTrue(i + " key is absent", result.contains(new ORecordId(i % 32000, i)));
      if (i % 100_000 == 0) {
        System.out.printf("%d items tested out of %d%n", i, keysCount);
      }
    }

    for (int i = keysCount; i < 2 * keysCount; i++) {
      Assert.assertTrue(multiValueTree.get(Integer.toString(i)).isEmpty());
    }
  }

  @Test
  public void testKeyPutRandomUniform() throws Exception {
    final NavigableMap<String, Integer> keys = new TreeMap<>();
    long seed = System.nanoTime();
    System.out.println("testKeyPutRandomUniform : " + seed);
    final Random random = new Random(seed);
    final int keysCount = 1_000_000;

    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();
    while (keys.size() < keysCount) {
      int val = random.nextInt(Integer.MAX_VALUE);
      String key = Integer.toString(val);

      for (int k = 0; k < 2; k++) {
        atomicOperationsManager.startAtomicOperation((String) null, false);
        multiValueTree.put(key, new ORecordId(val % 32000, val));
        atomicOperationsManager.endAtomicOperation(k == 0);
      }

      keys.compute(key, (k, v) -> {
        if (v == null) {
          return 1;
        }

        return v + 1;
      });

      final List<ORID> result = multiValueTree.get(key);
      Assert.assertEquals(keys.get(key).longValue(), result.size());
      final ORID expected = new ORecordId(val % 32000, val);

      for (ORID rid : result) {
        Assert.assertEquals(expected, rid);
      }
    }

    Assert.assertEquals(multiValueTree.firstKey(), keys.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keys.lastKey());

    for (Map.Entry<String, Integer> entry : keys.entrySet()) {
      final int val = Integer.parseInt(entry.getKey());
      List<ORID> result = multiValueTree.get(entry.getKey());

      Assert.assertEquals(entry.getValue().longValue(), result.size());
      final ORID expected = new ORecordId(val % 32000, val);

      for (ORID rid : result) {
        Assert.assertEquals(expected, rid);
      }
    }
  }

  @Test
  public void testKeyDelete() throws Exception {
    final int keysCount = 1_000_000;

    NavigableMap<String, Integer> keys = new TreeMap<>();
    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

    doInRollbackLoop(0, keysCount, 100, (value, rollback) -> {
      String key = Integer.toString(value);

      multiValueTree.put(key, new ORecordId(value % 32000, value));

      if (!rollback) {
        keys.compute(key, (k, v) -> {
          if (v == null) {
            return 1;
          }

          return v + 1;
        });
      }
    });

    Iterator<Map.Entry<String, Integer>> iterator = keys.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, Integer> entry = iterator.next();
      String key = entry.getKey();
      int val = Integer.parseInt(key);

      if (val % 3 == 0) {
        for (int k = 0; k < 2; k++) {
          atomicOperationsManager.startAtomicOperation((String) null, false);
          multiValueTree.remove(key, new ORecordId(val % 32000, val));
          atomicOperationsManager.endAtomicOperation(k == 0);
        }
        if (entry.getValue() == 1) {
          iterator.remove();
        } else {
          entry.setValue(entry.getValue() - 1);
        }

      }
    }

    Assert.assertEquals(multiValueTree.firstKey(), keys.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keys.lastKey());

    for (int i = 0; i < keysCount; i++) {
      final String key = String.valueOf(i);

      if (i % 3 == 0) {
        Assert.assertTrue(multiValueTree.get(key).isEmpty());
      } else {
        List<ORID> result = multiValueTree.get(key);

        Assert.assertEquals(1, result.size());
        final ORID expected = new ORecordId(i % 32000, i);

        for (ORID rid : result) {
          Assert.assertEquals(expected, rid);
        }
      }

    }
  }

  @Test
  public void testKeyAddDelete() throws Exception {
    final int keysCount = 1_000_000;

    doInRollbackLoop(0, keysCount, 100,
        (value, rollback) -> multiValueTree.put(Integer.toString(value), new ORecordId(value % 32000, value)));

    doInRollbackLoop(0, keysCount, 100, (value, rollback) -> {
      if (value % 3 == 0) {
        Assert.assertTrue(multiValueTree.remove(Integer.toString(value), new ORecordId(value % 32000, value)));
      }

      if (value % 2 == 0) {
        multiValueTree.put(Integer.toString(keysCount + value), new ORecordId((keysCount + value) % 32000, keysCount + value));
      }
    });

    for (int i = 0; i < keysCount; i++) {
      if (i % 3 == 0) {
        Assert.assertTrue(multiValueTree.get(Integer.toString(i)).isEmpty());
      } else {
        List<ORID> result = multiValueTree.get(Integer.toString(i));

        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.contains(new ORecordId(i % 32000, i)));
      }

      if (i % 2 == 0) {
        List<ORID> result = multiValueTree.get(Integer.toString(keysCount + i));

        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.contains(new ORecordId((keysCount + i) % 32000, keysCount + i)));
      }
    }
  }

  @Test
  public void testKeyCursor() throws Exception {
    final int keysCount = 1_000_000;

    NavigableMap<String, ORID> keyValues = new TreeMap<>();
    final long seed = System.nanoTime();

    System.out.println("testKeyCursor: " + seed);
    Random random = new Random(seed);

    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();
    while (keyValues.size() < keysCount) {
      int val = random.nextInt(Integer.MAX_VALUE);
      String key = Integer.toString(val);

      for (int k = 0; k < 2; k++) {
        atomicOperationsManager.startAtomicOperation((String) null, false);
        multiValueTree.put(key, new ORecordId(val % 32000, val));
        atomicOperationsManager.endAtomicOperation(k == 0);
      }

      keyValues.put(key, new ORecordId(val % 32000, val));
    }

    Assert.assertEquals(multiValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keyValues.lastKey());

    final OCellBTreeMultiValue.OCellBTreeKeyCursor<String> cursor = multiValueTree.keyCursor();

    for (String entryKey : keyValues.keySet()) {
      final String indexKey = cursor.next(-1);
      Assert.assertEquals(entryKey, indexKey);
    }
  }

  @Test
  public void testIterateEntriesMajor() throws Exception {
    final int keysCount = 1_000_000;

    NavigableMap<String, Integer> keyValues = new TreeMap<>();
    final long seed = System.nanoTime();

    System.out.println("testIterateEntriesMajor: " + seed);
    Random random = new Random(seed);

    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();
    while (keyValues.size() < keysCount) {
      int val = random.nextInt(Integer.MAX_VALUE);
      String key = Integer.toString(val);

      for (int k = 0; k < 2; k++) {
        atomicOperationsManager.startAtomicOperation((String) null, false);
        multiValueTree.put(key, new ORecordId(val % 32000, val));
        atomicOperationsManager.endAtomicOperation(k == 0);
      }

      keyValues.compute(key, (k, v) -> {
        if (v == null) {
          return 1;
        }

        return v + 1;
      });
    }

    assertIterateMajorEntries(keyValues, random, true, true);
    assertIterateMajorEntries(keyValues, random, false, true);

    assertIterateMajorEntries(keyValues, random, true, false);
    assertIterateMajorEntries(keyValues, random, false, false);

    Assert.assertEquals(multiValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keyValues.lastKey());
  }

  @Test
  public void testIterateEntriesMinor() throws Exception {
    final int keysCount = 1_000_000;
    NavigableMap<String, Integer> keyValues = new TreeMap<>();

    final long seed = System.nanoTime();

    System.out.println("testIterateEntriesMinor: " + seed);
    Random random = new Random(seed);

    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();
    while (keyValues.size() < keysCount) {
      int val = random.nextInt(Integer.MAX_VALUE);
      String key = Integer.toString(val);

      for (int k = 0; k < 2; k++) {
        atomicOperationsManager.startAtomicOperation((String) null, false);
        multiValueTree.put(key, new ORecordId(val % 32000, val));
        atomicOperationsManager.endAtomicOperation(k == 0);
      }

      keyValues.compute(key, (k, v) -> {
        if (v == null) {
          return 1;
        }

        return v + 1;
      });
    }

    assertIterateMinorEntries(keyValues, random, true, true);
    assertIterateMinorEntries(keyValues, random, false, true);

    assertIterateMinorEntries(keyValues, random, true, false);
    assertIterateMinorEntries(keyValues, random, false, false);

    Assert.assertEquals(multiValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keyValues.lastKey());
  }

  @Test
  public void testIterateEntriesBetween() throws Exception {
    final int keysCount = 1_000_000;
    NavigableMap<String, Integer> keyValues = new TreeMap<>();
    Random random = new Random();

    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

    while (keyValues.size() < keysCount) {
      int val = random.nextInt(Integer.MAX_VALUE);
      String key = Integer.toString(val);

      for (int k = 0; k < 2; k++) {
        atomicOperationsManager.startAtomicOperation((String) null, false);
        multiValueTree.put(key, new ORecordId(val % 32000, val));
        atomicOperationsManager.endAtomicOperation(k == 0);
      }

      keyValues.compute(key, (k, v) -> {
        if (v == null) {
          return 1;
        }

        return v + 1;
      });
    }

    assertIterateBetweenEntries(keyValues, random, true, true, true);
    assertIterateBetweenEntries(keyValues, random, true, false, true);
    assertIterateBetweenEntries(keyValues, random, false, true, true);
    assertIterateBetweenEntries(keyValues, random, false, false, true);

    assertIterateBetweenEntries(keyValues, random, true, true, false);
    assertIterateBetweenEntries(keyValues, random, true, false, false);
    assertIterateBetweenEntries(keyValues, random, false, true, false);
    assertIterateBetweenEntries(keyValues, random, false, false, false);

    Assert.assertEquals(multiValueTree.firstKey(), keyValues.firstKey());
    Assert.assertEquals(multiValueTree.lastKey(), keyValues.lastKey());
  }

  private void assertIterateMajorEntries(NavigableMap<String, Integer> keyValues, Random random, boolean keyInclusive,
      boolean ascSortOrder) {
    String[] keys = new String[keyValues.size()];
    int index = 0;

    for (String key : keyValues.keySet()) {
      keys[index] = key;
      index++;
    }

    for (int i = 0; i < 100; i++) {
      final int fromKeyIndex = random.nextInt(keys.length);
      String fromKey = keys[fromKeyIndex];

      if (random.nextBoolean()) {
        fromKey = fromKey.substring(0, fromKey.length() - 1) + (char) (fromKey.charAt(fromKey.length() - 1) - 1);
      }

      final OCellBTreeMultiValue.OCellBTreeCursor<String, ORID> cursor = multiValueTree
          .iterateEntriesMajor(fromKey, keyInclusive, ascSortOrder);

      Iterator<Map.Entry<String, Integer>> iterator;
      if (ascSortOrder) {
        iterator = keyValues.tailMap(fromKey, keyInclusive).entrySet().iterator();
      } else {
        iterator = keyValues.descendingMap().subMap(keyValues.lastKey(), true, fromKey, keyInclusive).entrySet().iterator();
      }

      while (iterator.hasNext()) {
        Map.Entry<String, ORID> indexEntry = cursor.next(-1);
        final Map.Entry<String, Integer> entry = iterator.next();

        final int repetition = entry.getValue();
        final int value = Integer.parseInt(entry.getKey());
        final ORID expected = new ORecordId(value % 32_000, value);

        Assert.assertEquals(entry.getKey(), indexEntry.getKey());
        Assert.assertEquals(expected, indexEntry.getValue());

        for (int n = 1; n < repetition; n++) {
          indexEntry = cursor.next(-1);

          Assert.assertEquals(entry.getKey(), indexEntry.getKey());
          Assert.assertEquals(expected, indexEntry.getValue());
        }
      }

      //noinspection ConstantConditions
      Assert.assertFalse(iterator.hasNext());
      Assert.assertNull(cursor.next(-1));
    }
  }

  private void assertIterateMinorEntries(NavigableMap<String, Integer> keyValues, Random random, boolean keyInclusive,
      boolean ascSortOrder) {
    String[] keys = new String[keyValues.size()];
    int index = 0;

    for (String key : keyValues.keySet()) {
      keys[index] = key;
      index++;
    }

    for (int i = 0; i < 100; i++) {
      int toKeyIndex = random.nextInt(keys.length);
      String toKey = keys[toKeyIndex];
      if (random.nextBoolean()) {
        toKey = toKey.substring(0, toKey.length() - 1) + (char) (toKey.charAt(toKey.length() - 1) + 1);
      }

      final OCellBTreeMultiValue.OCellBTreeCursor<String, ORID> cursor = multiValueTree
          .iterateEntriesMinor(toKey, keyInclusive, ascSortOrder);

      Iterator<Map.Entry<String, Integer>> iterator;
      if (ascSortOrder) {
        iterator = keyValues.headMap(toKey, keyInclusive).entrySet().iterator();
      } else {
        iterator = keyValues.headMap(toKey, keyInclusive).descendingMap().entrySet().iterator();
      }

      while (iterator.hasNext()) {
        Map.Entry<String, ORID> indexEntry = cursor.next(-1);
        Map.Entry<String, Integer> entry = iterator.next();

        final int repetition = entry.getValue();
        final int value = Integer.parseInt(entry.getKey());
        final ORID expected = new ORecordId(value % 32_000, value);

        Assert.assertEquals(entry.getKey(), indexEntry.getKey());
        Assert.assertEquals(expected, indexEntry.getValue());

        for (int n = 1; n < repetition; n++) {
          indexEntry = cursor.next(-1);

          Assert.assertEquals(entry.getKey(), indexEntry.getKey());
          Assert.assertEquals(expected, indexEntry.getValue());
        }
      }

      //noinspection ConstantConditions
      Assert.assertFalse(iterator.hasNext());
      Assert.assertNull(cursor.next(-1));
    }
  }

  private void assertIterateBetweenEntries(NavigableMap<String, Integer> keyValues, Random random, boolean fromInclusive,
      boolean toInclusive, boolean ascSortOrder) {
    String[] keys = new String[keyValues.size()];
    int index = 0;

    for (String key : keyValues.keySet()) {
      keys[index] = key;
      index++;
    }

    for (int i = 0; i < 100; i++) {
      int fromKeyIndex = random.nextInt(keys.length);
      int toKeyIndex = random.nextInt(keys.length);

      if (fromKeyIndex > toKeyIndex) {
        toKeyIndex = fromKeyIndex;
      }

      String fromKey = keys[fromKeyIndex];
      String toKey = keys[toKeyIndex];

      if (random.nextBoolean()) {
        fromKey = fromKey.substring(0, fromKey.length() - 1) + (char) (fromKey.charAt(fromKey.length() - 1) - 1);
      }

      if (random.nextBoolean()) {
        toKey = toKey.substring(0, toKey.length() - 1) + (char) (toKey.charAt(toKey.length() - 1) + 1);
      }

      if (fromKey.compareTo(toKey) > 0) {
        fromKey = toKey;
      }

      OCellBTreeMultiValue.OCellBTreeCursor<String, ORID> cursor = multiValueTree
          .iterateEntriesBetween(fromKey, fromInclusive, toKey, toInclusive, ascSortOrder);

      Iterator<Map.Entry<String, Integer>> iterator;
      if (ascSortOrder) {
        iterator = keyValues.subMap(fromKey, fromInclusive, toKey, toInclusive).entrySet().iterator();
      } else {
        iterator = keyValues.descendingMap().subMap(toKey, toInclusive, fromKey, fromInclusive).entrySet().iterator();
      }

      while (iterator.hasNext()) {
        Map.Entry<String, ORID> indexEntry = cursor.next(-1);
        Assert.assertNotNull(indexEntry);

        Map.Entry<String, Integer> entry = iterator.next();

        final int repetition = entry.getValue();
        final int value = Integer.parseInt(entry.getKey());
        final ORID expected = new ORecordId(value % 32_000, value);

        Assert.assertEquals(entry.getKey(), indexEntry.getKey());
        Assert.assertEquals(expected, indexEntry.getValue());

        for (int n = 1; n < repetition; n++) {
          indexEntry = cursor.next(-1);

          Assert.assertEquals(entry.getKey(), indexEntry.getKey());
          Assert.assertEquals(expected, indexEntry.getValue());
        }
      }
      //noinspection ConstantConditions
      Assert.assertFalse(iterator.hasNext());
      Assert.assertNull(cursor.next(-1));
    }
  }

  private void doInRollbackLoop(final int start, final int end, @SuppressWarnings("SameParameterValue") final int rollbackSlice,
      final TxCode code) throws IOException {
    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

    for (int i = start; i < end; i += rollbackSlice) {
      for (int k = 0; k < 2; k++) {
        atomicOperationsManager.startAtomicOperation((String) null, false);

        int counter = 0;
        while (counter < rollbackSlice && i + counter < end) {
          code.execute(i + counter, k == 0);

          counter++;
        }

        atomicOperationsManager.endAtomicOperation(k == 0);
      }
    }
  }

  private interface TxCode {
    void execute(int value, boolean rollback) throws IOException;
  }
}
