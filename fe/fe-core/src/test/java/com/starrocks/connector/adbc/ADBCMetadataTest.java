// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.adbc;

import com.starrocks.catalog.ADBCPartitionKey;
import com.starrocks.catalog.ADBCTable;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.NullablePartitionKey;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.Table;
import com.starrocks.connector.ConnectorPartitionTraits;
import com.starrocks.connector.PartitionInfo;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.statistic.ExternalFullStatisticsCollectJob;
import com.starrocks.statistic.StatsConstants;
import com.starrocks.type.IntegerType;
import mockit.Expectations;
import mockit.Mocked;
import mockit.Verifications;
import org.apache.arrow.adbc.core.AdbcConnection;
import org.apache.arrow.adbc.core.AdbcDatabase;
import org.apache.arrow.adbc.core.AdbcException;
import org.apache.arrow.adbc.core.AdbcStatusCode;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ADBCMetadataTest {

    @Mocked
    AdbcDatabase mockDatabase;

    @Mocked
    AdbcConnection mockConnection;

    @Mocked
    ArrowReader mockReader;

    @Mocked
    VectorSchemaRoot mockRoot;

    @Mocked
    VarCharVector mockCatNameVec;

    @Mocked
    ListVector mockDbSchemasVec;

    private Map<String, String> properties;
    private ADBCMetadata metadata;

    @BeforeEach
    public void setUp() {
        properties = new HashMap<>();
        properties.put("type", "adbc");
        properties.put("driver_url", "/mock/path/libadbc_driver_sqlite.so");
        properties.put("uri", ":memory:");
    }

    /**
     * Set up expectations for hierarchy resolution via getObjects(DB_SCHEMAS).
     * Returns no non-empty schemas, so the hierarchy model defaults to CATALOG.
     */
    private void expectHierarchyResolution() throws Exception {
        // getObjects for hierarchy resolution returns reader with no batches
        // (no non-empty schemas found -> CATALOG model)
    }

    @Test
    public void testListDbNames() throws Exception {
        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                // getObjects called for hierarchy resolution + listDbNames
                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                // First batch for hierarchy resolution: no schemas -> CATALOG model
                // Then for listDbNames: catalogs returned
                mockReader.loadNextBatch();
                returns(false, true, false);

                mockReader.getVectorSchemaRoot();
                result = mockRoot;

                mockRoot.getVector("catalog_db_schemas");
                result = mockDbSchemasVec;

                mockRoot.getVector("catalog_name");
                result = mockCatNameVec;

                mockRoot.getRowCount();
                result = 2;

                mockCatNameVec.isNull(0);
                result = false;
                mockCatNameVec.get(0);
                result = "db1".getBytes();

                mockCatNameVec.isNull(1);
                result = false;
                mockCatNameVec.get(1);
                result = "db2".getBytes();
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        List<String> dbNames = metadata.listDbNames(null);

        assertNotNull(dbNames);
        assertEquals(2, dbNames.size());
        assertTrue(dbNames.contains("db1"));
        assertTrue(dbNames.contains("db2"));
    }

    @Test
    public void testListDbNames_emptyReturnsMain() throws Exception {
        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                // Both hierarchy resolution and listDbNames return no batches
                mockReader.loadNextBatch();
                result = false;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        List<String> dbNames = metadata.listDbNames(null);

        assertNotNull(dbNames);
        assertEquals(1, dbNames.size());
        assertEquals("main", dbNames.get(0));
    }

    @Test
    public void testListTableNames() throws Exception {
        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                // Hierarchy resolution: no batches -> CATALOG model
                // listTableNames: no batches (empty table list)
                mockReader.loadNextBatch();
                result = false;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        List<String> tableNames = metadata.listTableNames(null, "mydb");

        assertNotNull(tableNames);
        // With CATALOG model, getObjects is called with catalogFilter="mydb",
        // but no batches returned -> empty list
        assertTrue(tableNames.isEmpty());
    }

    @Test
    public void testGetTable() throws Exception {
        Schema arrowSchema = new Schema(Arrays.asList(
                new Field("id", FieldType.nullable(new ArrowType.Int(32, true)), Collections.emptyList()),
                new Field("name", FieldType.nullable(ArrowType.Utf8.INSTANCE), Collections.emptyList())
        ));

        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                // getObjects for hierarchy resolution
                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                mockReader.loadNextBatch();
                result = false;

                // getTableSchema returns the arrow schema directly
                mockConnection.getTableSchema(anyString, anyString, anyString);
                result = arrowSchema;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        Table table = metadata.getTable(null, "mydb", "tbl1");

        assertNotNull(table);
        assertInstanceOf(ADBCTable.class, table);
        ADBCTable adbcTable = (ADBCTable) table;
        assertEquals("tbl1", adbcTable.getName());
        assertEquals("mydb", adbcTable.getDbName());
        assertEquals("test_catalog", adbcTable.getCatalogName());

        List<Column> columns = adbcTable.getFullSchema();
        assertEquals(2, columns.size());
        assertEquals("id", columns.get(0).getName());
        assertEquals("name", columns.get(1).getName());
    }

    @Test
    public void testGetTable_nullSchema() throws Exception {
        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                mockReader.loadNextBatch();
                result = false;

                mockConnection.getTableSchema(anyString, anyString, anyString);
                result = null;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        Table table = metadata.getTable(null, "mydb", "nonexistent");

        // getTableSchema returns null -> StarRocksConnectorException -> getTable returns null
        assertNull(table);
    }

    @Test
    public void testGetTable_emptySchema() throws Exception {
        // A schema with only unsupported types (all fields skipped)
        Schema arrowSchema = new Schema(Collections.singletonList(
                new Field("complex", FieldType.nullable(ArrowType.Struct.INSTANCE),
                        Collections.singletonList(
                                new Field("sub", FieldType.nullable(new ArrowType.Int(32, true)),
                                        Collections.emptyList())))
        ));

        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                mockReader.loadNextBatch();
                result = false;

                mockConnection.getTableSchema(anyString, anyString, anyString);
                result = arrowSchema;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        Table table = metadata.getTable(null, "mydb", "empty_tbl");

        assertNull(table);
    }

    @Test
    public void testGetTable_stableTableId() throws Exception {
        Schema arrowSchema = new Schema(Arrays.asList(
                new Field("id", FieldType.nullable(new ArrowType.Int(32, true)), Collections.emptyList())
        ));

        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                mockReader.loadNextBatch();
                result = false;

                // getTableSchema called twice (no cache, direct call each time)
                mockConnection.getTableSchema(anyString, anyString, anyString);
                result = arrowSchema;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        Table table1 = metadata.getTable(null, "mydb", "tbl1");
        Table table2 = metadata.getTable(null, "mydb", "tbl1");

        // Both calls fetch fresh metadata but get the same stable table ID from tableIdMap
        assertNotNull(table1);
        assertNotNull(table2);
        assertEquals(table1.getId(), table2.getId());
    }

    @Test
    public void testShutdown() throws Exception {
        new Expectations() {{
                mockDatabase.close();
                times = 1;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        metadata.shutdown();
        // Should not throw
    }

    @Test
    public void testShutdown_withException() throws Exception {
        new Expectations() {{
                mockDatabase.close();
                result = new Exception("close error");
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        metadata.shutdown();
        // Should not throw, just log warning
    }

    @Test
    public void testGetCatalogTableName() {
        List<Column> cols = Arrays.asList(
                new Column("col1", IntegerType.INT)
        );
        ADBCTable adbcTable = new ADBCTable(1, "test_table", cols, "mydb", "my_catalog", properties);
        assertEquals("my_catalog.mydb.test_table", adbcTable.getCatalogTableName());
    }

    @Test
    public void testPartitionTraitsRegistered() {
        assertTrue(ConnectorPartitionTraits.isSupported(Table.TableType.ADBC));
    }

    @Test
    public void testPartitionTraitsNoPCT() {
        assertFalse(ConnectorPartitionTraits.isSupportPCTRefresh(Table.TableType.ADBC));
    }

    @Test
    public void testListPartitionNames_default() {
        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        List<String> partitionNames = metadata.listPartitionNames("mydb", "tbl1", null);
        assertNotNull(partitionNames);
        assertTrue(partitionNames.isEmpty());
    }

    // ==================== MV Support Verification Tests ====================

    @Test
    public void testMVSupportedTableType() {
        assertTrue(ConnectorPartitionTraits.isSupported(Table.TableType.ADBC));
    }

    @Test
    public void testADBCPartitionTraitsGetTableName() {
        List<Column> cols = Arrays.asList(new Column("id", IntegerType.INT));
        ADBCTable adbcTable = new ADBCTable(1, "orders", cols, "sales_db", "flight_catalog", properties);

        ConnectorPartitionTraits fullTraits = ConnectorPartitionTraits.build(adbcTable);

        assertEquals("flight_catalog.sales_db.orders", fullTraits.getTableName());
    }

    @Test
    public void testADBCPartitionTraitsGetPartitions() {
        List<Column> cols = Arrays.asList(new Column("id", IntegerType.INT));
        ADBCTable adbcTable = new ADBCTable(1, "tbl", cols, "db", "cat", properties);

        ConnectorPartitionTraits traits = ConnectorPartitionTraits.build(adbcTable);
        List<PartitionInfo> partitions = traits.getPartitions(Arrays.asList("p1", "p2"));

        assertNotNull(partitions);
        assertTrue(partitions.isEmpty());
    }

    @Test
    public void testADBCPartitionTraitsCreateEmptyKey() {
        ConnectorPartitionTraits traits = ConnectorPartitionTraits.build(Table.TableType.ADBC);
        PartitionKey key = traits.createEmptyKey();

        assertNotNull(key);
        assertInstanceOf(ADBCPartitionKey.class, key);
        assertInstanceOf(NullablePartitionKey.class, key);
    }

    @Test
    public void testRefreshTable_isNoOp() throws Exception {
        Schema arrowSchema = new Schema(Arrays.asList(
                new Field("id", FieldType.nullable(new ArrowType.Int(32, true)), Collections.emptyList())
        ));

        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                mockReader.loadNextBatch();
                result = false;

                mockConnection.getTableSchema(anyString, anyString, anyString);
                result = arrowSchema;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        Table table1 = metadata.getTable(null, "mydb", "tbl1");
        assertNotNull(table1);

        // refreshTable is a no-op -- should not throw
        metadata.refreshTable("mydb", table1, Collections.emptyList(), false);

        // After refresh, getTable re-fetches but table ID stays stable via tableIdMap
        Table table2 = metadata.getTable(null, "mydb", "tbl1");
        assertNotNull(table2);
        assertEquals(table1.getId(), table2.getId());
    }

    @Test
    public void testClear() throws Exception {
        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        // clear() resets tableIdMap and hierarchyModel without throwing
        assertDoesNotThrow(() -> metadata.clear());
    }

    @Test
    public void testAnalyzeTableInfraAcceptsADBCTable(@Mocked Database mockDb) {
        List<Column> cols = Arrays.asList(
                new Column("col1", IntegerType.INT)
        );
        ADBCTable adbcTable = new ADBCTable(1, "test_tbl", cols, "mydb", "test_catalog", properties);

        assertDoesNotThrow(() -> {
            ExternalFullStatisticsCollectJob job = new ExternalFullStatisticsCollectJob(
                    "test_catalog",
                    mockDb,
                    adbcTable,
                    Collections.emptyList(),      // partitionNames
                    Collections.singletonList("col1"),  // columnNames
                    Collections.singletonList(IntegerType.INT),  // columnTypes
                    StatsConstants.AnalyzeType.FULL,
                    StatsConstants.ScheduleType.ONCE,
                    new HashMap<>()               // properties
            );
            assertNotNull(job);
        });
    }

    @Test
    public void testConnectionPoolReusesConnections() throws Exception {
        // If the pool were absent, connect() would be invoked 10 times and jmockit
        // would fail at iteration 9. With pooling, sequential calls reuse one
        // connection, so connect() is invoked exactly 1 time. maxTimes=8 is the
        // pool's hard ceiling and a generous upper bound.
        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                maxTimes = 8;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                mockReader.loadNextBatch();
                result = false;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        for (int i = 0; i < 10; i++) {
            List<String> dbNames = metadata.listDbNames(null);
            assertNotNull(dbNames);
            assertEquals(1, dbNames.size());
            assertEquals("main", dbNames.get(0));
        }
    }

    // ==================== Hierarchy state-machine tests ====================
    //
    // The class header in ADBCMetadata documents three reachable states (CATALOG,
    // SCHEMA, UNKNOWN) plus the UNKNOWN -> {SCHEMA, CATALOG} upgrade path. The
    // tests below exercise every transition, since the original suite only
    // covered the CATALOG branch (resolveHierarchy returning empty schemas).

    @Test
    public void testResolveHierarchy_schemaModel() throws Exception {
        // Arrange: getObjects returns one batch with a non-empty db_schema_name.
        // resolveHierarchy must classify this as SCHEMA, and listDbNames must
        // surface the schema name (not the catalog name).
        Map<String, String> schemaEntry = new HashMap<>();
        schemaEntry.put("db_schema_name", "public");
        List<Map<String, String>> schemaList = Collections.singletonList(schemaEntry);

        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                // Two getObjects calls: resolveHierarchy() and listDbNamesFromGetObjects().
                // Each yields exactly one batch, so loadNextBatch returns true,false,true,false.
                mockReader.loadNextBatch();
                returns(true, false, true, false);

                mockReader.getVectorSchemaRoot();
                result = mockRoot;

                mockRoot.getVector("catalog_db_schemas");
                result = mockDbSchemasVec;

                mockRoot.getVector("catalog_name");
                result = mockCatNameVec;
                minTimes = 0;

                mockRoot.getRowCount();
                result = 1;

                mockDbSchemasVec.isNull(0);
                result = false;

                mockDbSchemasVec.getObject(0);
                result = schemaList;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        List<String> dbNames = metadata.listDbNames(null);

        assertNotNull(dbNames);
        assertEquals(1, dbNames.size());
        assertEquals("public", dbNames.get(0));
    }

    @Test
    public void testResolveHierarchy_unknownFromNotImplemented() throws Exception {
        // Arrange: getObjects throws NOT_IMPLEMENTED -> hierarchy = UNKNOWN.
        // listDbNames must short-circuit getObjects (per the UNKNOWN branch in
        // listDbNamesFromGetObjects) and go straight to the SQL fallback.
        // The SQL fallback also fails here, so the final default ("main") is returned.
        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = AdbcException.notImplemented("getObjects not implemented");

                // Both SQL fallback queries fail at createStatement -> default "main".
                mockConnection.createStatement();
                result = new RuntimeException("simulated SQL failure");
                minTimes = 0;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        List<String> dbNames = metadata.listDbNames(null);

        assertEquals(Collections.singletonList("main"), dbNames);

        // The UNKNOWN branch must NOT call getObjects a second time.
        // Exactly one call: the initial resolveHierarchy attempt.
        new Verifications() {{
                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                times = 1;
            }};
    }

    @Test
    public void testGetTable_unknownToSchemaUpgrade() throws Exception {
        // Arrange: getObjects throws NOT_IMPLEMENTED on the first call (-> UNKNOWN).
        // Probing then succeeds at the SCHEMA position, which must upgrade
        // hierarchyModel to SCHEMA and skip getObjects on subsequent calls.
        Schema arrowSchema = new Schema(Collections.singletonList(
                new Field("id", FieldType.nullable(new ArrowType.Int(32, true)), Collections.emptyList())
        ));

        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = AdbcException.notImplemented("getObjects not implemented");

                // Schema-position probe succeeds the first time, and the SECOND
                // getTable call goes here directly (model is now SCHEMA).
                mockConnection.getTableSchema(null, anyString, anyString);
                result = arrowSchema;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);

        Table t1 = metadata.getTable(null, "mydb", "tbl1");
        Table t2 = metadata.getTable(null, "mydb", "tbl2");

        assertNotNull(t1);
        assertNotNull(t2);

        // After upgrade: getObjects should be called exactly once (the failing
        // initial attempt). If the upgrade did not stick, the second getTable
        // would re-enter resolveHierarchy and bump this count.
        new Verifications() {{
                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                times = 1;

                // Both calls used the SCHEMA position (catalogArg=null).
                mockConnection.getTableSchema(null, "mydb", "tbl1");
                times = 1;
                mockConnection.getTableSchema(null, "mydb", "tbl2");
                times = 1;
            }};
    }

    @Test
    public void testGetTable_unknownToCatalogUpgrade() throws Exception {
        // Arrange: getObjects -> NOT_IMPLEMENTED, schema-position probe misses
        // (NOT_FOUND), catalog-position probe succeeds. Hierarchy must upgrade
        // to CATALOG, and the second getTable call must use the catalog position.
        Schema arrowSchema = new Schema(Collections.singletonList(
                new Field("id", FieldType.nullable(new ArrowType.Int(32, true)), Collections.emptyList())
        ));

        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = AdbcException.notImplemented("getObjects not implemented");

                // Schema position misses on first probe.
                mockConnection.getTableSchema(null, "mydb", "tbl1");
                result = new AdbcException("not found", null,
                        AdbcStatusCode.NOT_FOUND, null, 0);

                // Catalog position succeeds (first probe + cached path for second getTable).
                mockConnection.getTableSchema("mydb", null, anyString);
                result = arrowSchema;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);

        Table t1 = metadata.getTable(null, "mydb", "tbl1");
        Table t2 = metadata.getTable(null, "mydb", "tbl2");

        assertNotNull(t1);
        assertNotNull(t2);

        new Verifications() {{
                // After upgrade: only one getObjects call (the initial failure).
                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                times = 1;

                // First getTable: probed schema (missed), then catalog (hit).
                mockConnection.getTableSchema(null, "mydb", "tbl1");
                times = 1;
                mockConnection.getTableSchema("mydb", null, "tbl1");
                times = 1;

                // Second getTable: hierarchy is CATALOG, so no more schema-position probe.
                mockConnection.getTableSchema(null, "mydb", "tbl2");
                times = 0;
                mockConnection.getTableSchema("mydb", null, "tbl2");
                times = 1;
            }};
    }

    @Test
    public void testClearForcesReresolution() throws Exception {
        // Arrange: every getObjects call returns no batches (-> CATALOG model
        // with no rows -> SQL fallback returns "main").
        // Calling clear() between two listDbNames invocations must reset
        // hierarchyModel to null and force resolveHierarchy() to run again.
        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                mockReader.loadNextBatch();
                result = false;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);

        metadata.listDbNames(null);  // resolves hierarchy (call #1 to getObjects)
        metadata.listDbNames(null);  // hierarchy cached -> no extra getObjects
        metadata.clear();            // resets hierarchyModel to null
        metadata.listDbNames(null);  // re-resolves (call #2 to getObjects)

        // Two getObjects calls per listDbNames cycle (resolveHierarchy + the
        // catalog-listing call), so 2 cycles = 4 total. The middle listDbNames
        // skips resolveHierarchy and only does the catalog-listing call,
        // contributing 1. Total expected: 2 + 1 + 2 = 5.
        new Verifications() {{
                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                times = 5;
            }};
    }

    @Test
    public void testShutdownClosesPoolAndDatabase() throws Exception {
        new Expectations() {{
                mockDatabase.connect();
                result = mockConnection;
                minTimes = 0;

                mockConnection.getObjects(
                        (AdbcConnection.GetObjectsDepth) any,
                        anyString, anyString, anyString, (String[]) any, anyString);
                result = mockReader;
                minTimes = 0;

                mockReader.loadNextBatch();
                result = false;

                mockDatabase.close();
                times = 1;
            }};

        metadata = new ADBCMetadata(properties, "test_catalog", mockDatabase);
        List<String> dbNames = metadata.listDbNames(null);
        assertNotNull(dbNames);
        assertEquals(1, dbNames.size());
        assertEquals("main", dbNames.get(0));

        metadata.shutdown();

        assertThrows(StarRocksConnectorException.class, () -> metadata.listDbNames(null));
    }
}
