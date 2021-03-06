/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.coprocessor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.hadoop.hbase.io.hfile.HFileContextBuilder;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.RegionCoprocessorHost;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.apache.hadoop.hbase.util.Threads;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(MediumTests.class)
public class TestRegionObserverInterface {
  static final Log LOG = LogFactory.getLog(TestRegionObserverInterface.class);

  public static final TableName TEST_TABLE =
      TableName.valueOf("TestTable");
  public final static byte[] A = Bytes.toBytes("a");
  public final static byte[] B = Bytes.toBytes("b");
  public final static byte[] C = Bytes.toBytes("c");
  public final static byte[] ROW = Bytes.toBytes("testrow");

  private static HBaseTestingUtility util = new HBaseTestingUtility();
  private static MiniHBaseCluster cluster = null;

  @BeforeClass
  public static void setupBeforeClass() throws Exception {
    // set configure to indicate which cp should be loaded
    Configuration conf = util.getConfiguration();
    conf.setBoolean("hbase.master.distributed.log.replay", true);
    conf.set(CoprocessorHost.REGION_COPROCESSOR_CONF_KEY,
        "org.apache.hadoop.hbase.coprocessor.SimpleRegionObserver");

    util.startMiniCluster();
    cluster = util.getMiniHBaseCluster();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    util.shutdownMiniCluster();
  }

  @Test
  public void testRegionObserver() throws IOException {
    TableName tableName = TEST_TABLE;
    // recreate table every time in order to reset the status of the
    // coprocessor.
    HTable table = util.createTable(tableName, new byte[][] {A, B, C});
    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreGet", "hadPostGet", "hadPrePut", "hadPostPut",
            "hadDelete"},
        TEST_TABLE,
        new Boolean[] {false, false, false, false, false});

    Put put = new Put(ROW);
    put.add(A, A, A);
    put.add(B, B, B);
    put.add(C, C, C);
    table.put(put);

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreGet", "hadPostGet", "hadPrePut", "hadPostPut",
            "hadPreBatchMutate", "hadPostBatchMutate", "hadDelete"},
        TEST_TABLE,
        new Boolean[] {false, false, true, true, true, true, false}
    );

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"getCtPreOpen", "getCtPostOpen", "getCtPreClose", "getCtPostClose"},
        TEST_TABLE,
        new Integer[] {1, 1, 0, 0});

    Get get = new Get(ROW);
    get.addColumn(A, A);
    get.addColumn(B, B);
    get.addColumn(C, C);
    table.get(get);

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreGet", "hadPostGet", "hadPrePut", "hadPostPut",
            "hadDelete"},
        TEST_TABLE,
        new Boolean[] {true, true, true, true, false}
    );

    Delete delete = new Delete(ROW);
    delete.deleteColumn(A, A);
    delete.deleteColumn(B, B);
    delete.deleteColumn(C, C);
    table.delete(delete);

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreGet", "hadPostGet", "hadPrePut", "hadPostPut",
            "hadPreBatchMutate", "hadPostBatchMutate", "hadDelete"},
        TEST_TABLE,
        new Boolean[] {true, true, true, true, true, true, true}
    );
    util.deleteTable(tableName);
    table.close();

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"getCtPreOpen", "getCtPostOpen", "getCtPreClose", "getCtPostClose"},
        TEST_TABLE,
        new Integer[] {1, 1, 1, 1});
  }

  @Test
  public void testRowMutation() throws IOException {
    TableName tableName = TEST_TABLE;
    HTable table = util.createTable(tableName, new byte[][] {A, B, C});
    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreGet", "hadPostGet", "hadPrePut", "hadPostPut",
            "hadDeleted"},
        TEST_TABLE,
        new Boolean[] {false, false, false, false, false});

    Put put = new Put(ROW);
    put.add(A, A, A);
    put.add(B, B, B);
    put.add(C, C, C);

    Delete delete = new Delete(ROW);
    delete.deleteColumn(A, A);
    delete.deleteColumn(B, B);
    delete.deleteColumn(C, C);

    RowMutations arm = new RowMutations(ROW);
    arm.add(put);
    arm.add(delete);
    table.mutateRow(arm);

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreGet", "hadPostGet", "hadPrePut", "hadPostPut",
            "hadDeleted"},
        TEST_TABLE,
        new Boolean[] {false, false, true, true, true}
    );
    util.deleteTable(tableName);
    table.close();
  }

  @Test
  public void testIncrementHook() throws IOException {
    TableName tableName = TEST_TABLE;

    HTable table = util.createTable(tableName, new byte[][] {A, B, C});
    Increment inc = new Increment(Bytes.toBytes(0));
    inc.addColumn(A, A, 1);

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreIncrement", "hadPostIncrement"},
        tableName,
        new Boolean[] {false, false}
    );

    table.increment(inc);

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreIncrement", "hadPostIncrement"},
        tableName,
        new Boolean[] {true, true}
    );
    util.deleteTable(tableName);
    table.close();
  }

  @Test
  // HBase-3583
  public void testHBase3583() throws IOException {
    TableName tableName =
        TableName.valueOf("testHBase3583");
    util.createTable(tableName, new byte[][] {A, B, C});

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreGet", "hadPostGet", "wasScannerNextCalled",
            "wasScannerCloseCalled"},
        tableName,
        new Boolean[] {false, false, false, false}
    );

    HTable table = new HTable(util.getConfiguration(), tableName);
    Put put = new Put(ROW);
    put.add(A, A, A);
    table.put(put);

    Get get = new Get(ROW);
    get.addColumn(A, A);
    table.get(get);

    // verify that scannerNext and scannerClose upcalls won't be invoked
    // when we perform get().
    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreGet", "hadPostGet", "wasScannerNextCalled",
            "wasScannerCloseCalled"},
        tableName,
        new Boolean[] {true, true, false, false}
    );

    Scan s = new Scan();
    ResultScanner scanner = table.getScanner(s);
    try {
      for (Result rr = scanner.next(); rr != null; rr = scanner.next()) {
      }
    } finally {
      scanner.close();
    }

    // now scanner hooks should be invoked.
    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"wasScannerNextCalled", "wasScannerCloseCalled"},
        tableName,
        new Boolean[] {true, true}
    );
    util.deleteTable(tableName);
    table.close();
  }

  @Test
  // HBase-3758
  public void testHBase3758() throws IOException {
    TableName tableName =
        TableName.valueOf("testHBase3758");
    util.createTable(tableName, new byte[][] {A, B, C});

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadDeleted", "wasScannerOpenCalled"},
        tableName,
        new Boolean[] {false, false}
    );

    HTable table = new HTable(util.getConfiguration(), tableName);
    Put put = new Put(ROW);
    put.add(A, A, A);
    table.put(put);

    Delete delete = new Delete(ROW);
    table.delete(delete);

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadDeleted", "wasScannerOpenCalled"},
        tableName,
        new Boolean[] {true, false}
    );

    Scan s = new Scan();
    ResultScanner scanner = table.getScanner(s);
    try {
      for (Result rr = scanner.next(); rr != null; rr = scanner.next()) {
      }
    } finally {
      scanner.close();
    }

    // now scanner hooks should be invoked.
    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"wasScannerOpenCalled"},
        tableName,
        new Boolean[] {true}
    );
    util.deleteTable(tableName);
    table.close();
  }

  /* Overrides compaction to only output rows with keys that are even numbers */
  public static class EvenOnlyCompactor extends BaseRegionObserver {
    long lastCompaction;
    long lastFlush;

    @Override
    public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> e,
        Store store, final InternalScanner scanner, final ScanType scanType) {
      return new InternalScanner() {
        @Override
        public boolean next(List<Cell> results) throws IOException {
          return next(results, -1);
        }

        @Override
        public boolean next(List<Cell> results, int limit)
            throws IOException{
          List<Cell> internalResults = new ArrayList<Cell>();
          boolean hasMore;
          do {
            hasMore = scanner.next(internalResults, limit);
            if (!internalResults.isEmpty()) {
              long row = Bytes.toLong(CellUtil.cloneValue(internalResults.get(0)));
              if (row % 2 == 0) {
                // return this row
                break;
              }
              // clear and continue
              internalResults.clear();
            }
          } while (hasMore);

          if (!internalResults.isEmpty()) {
            results.addAll(internalResults);
          }
          return hasMore;
        }

        @Override
        public void close() throws IOException {
          scanner.close();
        }
      };
    }

    @Override
    public void postCompact(ObserverContext<RegionCoprocessorEnvironment> e,
        Store store, StoreFile resultFile) {
      lastCompaction = EnvironmentEdgeManager.currentTimeMillis();
    }

    @Override
    public void postFlush(ObserverContext<RegionCoprocessorEnvironment> e) {
      lastFlush = EnvironmentEdgeManager.currentTimeMillis();
    }
  }
  /**
   * Tests overriding compaction handling via coprocessor hooks
   * @throws Exception
   */
  @Test
  public void testCompactionOverride() throws Exception {
    byte[] compactTable = Bytes.toBytes("TestCompactionOverride");
    HBaseAdmin admin = util.getHBaseAdmin();
    if (admin.tableExists(compactTable)) {
      admin.disableTable(compactTable);
      admin.deleteTable(compactTable);
    }

    HTableDescriptor htd = new HTableDescriptor(TableName.valueOf(compactTable));
    htd.addFamily(new HColumnDescriptor(A));
    htd.addCoprocessor(EvenOnlyCompactor.class.getName());
    admin.createTable(htd);

    HTable table = new HTable(util.getConfiguration(), compactTable);
    for (long i=1; i<=10; i++) {
      byte[] iBytes = Bytes.toBytes(i);
      Put put = new Put(iBytes);
      put.setDurability(Durability.SKIP_WAL);
      put.add(A, A, iBytes);
      table.put(put);
    }

    HRegion firstRegion = cluster.getRegions(compactTable).get(0);
    Coprocessor cp = firstRegion.getCoprocessorHost().findCoprocessor(
        EvenOnlyCompactor.class.getName());
    assertNotNull("EvenOnlyCompactor coprocessor should be loaded", cp);
    EvenOnlyCompactor compactor = (EvenOnlyCompactor)cp;

    // force a compaction
    long ts = System.currentTimeMillis();
    admin.flush(compactTable);
    // wait for flush
    for (int i=0; i<10; i++) {
      if (compactor.lastFlush >= ts) {
        break;
      }
      Thread.sleep(1000);
    }
    assertTrue("Flush didn't complete", compactor.lastFlush >= ts);
    LOG.debug("Flush complete");

    ts = compactor.lastFlush;
    admin.majorCompact(compactTable);
    // wait for compaction
    for (int i=0; i<30; i++) {
      if (compactor.lastCompaction >= ts) {
        break;
      }
      Thread.sleep(1000);
    }
    LOG.debug("Last compaction was at "+compactor.lastCompaction);
    assertTrue("Compaction didn't complete", compactor.lastCompaction >= ts);

    // only even rows should remain
    ResultScanner scanner = table.getScanner(new Scan());
    try {
      for (long i=2; i<=10; i+=2) {
        Result r = scanner.next();
        assertNotNull(r);
        assertFalse(r.isEmpty());
        byte[] iBytes = Bytes.toBytes(i);
        assertArrayEquals("Row should be "+i, r.getRow(), iBytes);
        assertArrayEquals("Value should be "+i, r.getValue(A, A), iBytes);
      }
    } finally {
      scanner.close();
    }
    table.close();
  }

  @Test
  public void bulkLoadHFileTest() throws Exception {
    String testName = TestRegionObserverInterface.class.getName()+".bulkLoadHFileTest";
    TableName tableName = TEST_TABLE;
    Configuration conf = util.getConfiguration();
    HTable table = util.createTable(tableName, new byte[][] {A, B, C});

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreBulkLoadHFile", "hadPostBulkLoadHFile"},
        tableName,
        new Boolean[] {false, false}
    );

    FileSystem fs = util.getTestFileSystem();
    final Path dir = util.getDataTestDirOnTestFS(testName).makeQualified(fs);
    Path familyDir = new Path(dir, Bytes.toString(A));

    createHFile(util.getConfiguration(), fs, new Path(familyDir,Bytes.toString(A)), A, A);

    //Bulk load
    new LoadIncrementalHFiles(conf).doBulkLoad(dir, new HTable(conf, tableName));

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreBulkLoadHFile", "hadPostBulkLoadHFile"},
        tableName,
        new Boolean[] {true, true}
    );
    util.deleteTable(tableName);
    table.close();
  }

  @Test
  public void testRecovery() throws Exception {
    LOG.info(TestRegionObserverInterface.class.getName()+".testRecovery");
    TableName tableName = TEST_TABLE;

    HTable table = util.createTable(tableName, new byte[][] {A, B, C});

    JVMClusterUtil.RegionServerThread rs1 = cluster.startRegionServer();
    ServerName sn2 = rs1.getRegionServer().getServerName();
    String regEN = table.getRegionLocations().firstEntry().getKey().getEncodedName();

    util.getHBaseAdmin().move(regEN.getBytes(), sn2.getServerName().getBytes());
    while (!sn2.equals(table.getRegionLocations().firstEntry().getValue() )){
      Thread.sleep(100);
    }

    Put put = new Put(ROW);
    put.add(A, A, A);
    put.add(B, B, B);
    put.add(C, C, C);
    table.put(put);

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"hadPreGet", "hadPostGet", "hadPrePut", "hadPostPut",
            "hadPreBatchMutate", "hadPostBatchMutate", "hadDelete"},
        TEST_TABLE,
        new Boolean[] {false, false, true, true, true, true, false}
    );

    verifyMethodResult(SimpleRegionObserver.class,
        new String[] {"getCtPreWALRestore", "getCtPostWALRestore", "getCtPrePut", "getCtPostPut"},
        TEST_TABLE,
        new Integer[] {0, 0, 1, 1});

    cluster.killRegionServer(rs1.getRegionServer().getServerName());
    Threads.sleep(20000); // just to be sure that the kill has fully started.
    util.waitUntilAllRegionsAssigned(tableName);

    verifyMethodResult(SimpleRegionObserver.class,
        new String[]{"getCtPreWALRestore", "getCtPostWALRestore"},
        TEST_TABLE,
        new Integer[]{1, 1});

    verifyMethodResult(SimpleRegionObserver.class,
        new String[]{"getCtPrePut", "getCtPostPut"},
        TEST_TABLE,
        new Integer[]{0, 0});

    util.deleteTable(tableName);
    table.close();
  }

  @Test
  public void testPreWALRestoreSkip() throws Exception {
    LOG.info(TestRegionObserverInterface.class.getName() + ".testPreWALRestoreSkip");
    TableName tableName = TableName.valueOf(SimpleRegionObserver.TABLE_SKIPPED);
    HTable table = util.createTable(tableName, new byte[][] { A, B, C });

    JVMClusterUtil.RegionServerThread rs1 = cluster.startRegionServer();
    ServerName sn2 = rs1.getRegionServer().getServerName();
    String regEN = table.getRegionLocations().firstEntry().getKey().getEncodedName();

    util.getHBaseAdmin().move(regEN.getBytes(), sn2.getServerName().getBytes());
    while (!sn2.equals(table.getRegionLocations().firstEntry().getValue())) {
      Thread.sleep(100);
    }

    Put put = new Put(ROW);
    put.add(A, A, A);
    put.add(B, B, B);
    put.add(C, C, C);
    table.put(put);
    table.flushCommits();

    cluster.killRegionServer(rs1.getRegionServer().getServerName());
    Threads.sleep(20000); // just to be sure that the kill has fully started.
    util.waitUntilAllRegionsAssigned(tableName);

    verifyMethodResult(SimpleRegionObserver.class, new String[] { "getCtPreWALRestore",
        "getCtPostWALRestore" }, tableName, new Integer[] { 0, 0 });

    util.deleteTable(tableName);
    table.close();
  }

  // check each region whether the coprocessor upcalls are called or not.
  private void verifyMethodResult(Class<?> c, String methodName[], TableName tableName,
                                  Object value[]) throws IOException {
    try {
      for (JVMClusterUtil.RegionServerThread t : cluster.getRegionServerThreads()) {
        if (!t.isAlive() || t.getRegionServer().isAborted() || t.getRegionServer().isStopping()){
          continue;
        }
        for (HRegionInfo r : ProtobufUtil.getOnlineRegions(t.getRegionServer())) {
          if (!r.getTable().equals(tableName)) {
            continue;
          }
          RegionCoprocessorHost cph = t.getRegionServer().getOnlineRegion(r.getRegionName()).
              getCoprocessorHost();

          Coprocessor cp = cph.findCoprocessor(c.getName());
          assertNotNull(cp);
          for (int i = 0; i < methodName.length; ++i) {
            Method m = c.getMethod(methodName[i]);
            Object o = m.invoke(cp);
            assertTrue("Result of " + c.getName() + "." + methodName[i]
                + " is expected to be " + value[i].toString()
                + ", while we get " + o.toString(), o.equals(value[i]));
          }
        }
      }
    } catch (Exception e) {
      throw new IOException(e.toString());
    }
  }

  private static void createHFile(
      Configuration conf,
      FileSystem fs, Path path,
      byte[] family, byte[] qualifier) throws IOException {
    HFileContext context = new HFileContextBuilder().build();
    HFile.Writer writer = HFile.getWriterFactory(conf, new CacheConfig(conf))
        .withPath(fs, path)
        .withFileContext(context)
        .create();
    long now = System.currentTimeMillis();
    try {
      for (int i =1;i<=9;i++) {
        KeyValue kv = new KeyValue(Bytes.toBytes(i+""), family, qualifier, now, Bytes.toBytes(i+""));
        writer.append(kv);
      }
    } finally {
      writer.close();
    }
  }

}
