package org.cratedb.integrationtests;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Ordering;
import org.cratedb.SQLCrateClusterTest;
import org.cratedb.action.sql.SQLResponse;
import org.cratedb.sql.*;
import org.cratedb.sql.types.TimeStampSQLType;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.hamcrest.Matchers.*;

public class TransportSQLActionTest extends SQLCrateClusterTest {

    private SQLResponse response;

    private String copyFilePath = TransportSQLActionTest.class.getResource(
                                            "/essetup/data/copy").getPath();

    @Rule
    public ExpectedException expectedException = ExpectedException.none();


    @Override
    protected int numberOfNodes() {
        return 2;
    }

    @Override
    public Settings getSettings() {
        // set number of replicas to 0 for getting a green cluster when using only one node
        return randomSettingsBuilder().put("number_of_replicas", 0).build();
    }

    /**
     * override execute to store response in property for easier access
     */
    @Override
    protected SQLResponse execute(String stmt, Object[] args) {
        response = super.execute(stmt, args);
        return response;
    }

    @Test
    public void testSelectKeepsOrder() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        refresh();
        execute("select \"_id\" as b, \"_version\" as a from test");
        assertArrayEquals(new String[]{"b", "a"}, response.cols());
        assertEquals(1, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
    }

    @Test
    public void testSelectCountStar() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id2").setSource("{}").execute().actionGet();
        refresh();
        execute("select count(*) from test");
        assertEquals(1, response.rowCount());
        assertEquals(2L, response.rows()[0][0]);
    }

    @Test
    public void testSelectCountStarWithWhereClause() throws Exception {
        prepareCreate("test")
            .addMapping("default", "name", "type=string,index=not_analyzed").execute().actionGet();
        client().prepareIndex("test", "default", "id1").setSource("{\"name\": \"Arthur\"}").execute().actionGet();
        client().prepareIndex("test", "default", "id2").setSource("{\"name\": \"Trillian\"}").execute().actionGet();
        refresh();
        execute("select count(*) from test where name = 'Trillian'");
        assertEquals(1, response.rowCount());
        assertEquals(1L, response.rows()[0][0]);
    }

    @Test
    public void testSelectStar() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                    "firstName", "type=string",
                    "lastName", "type=string")
                .execute().actionGet();
        execute("select * from test");
        assertArrayEquals(new String[]{"firstName", "lastName"}, response.cols());
        assertEquals(0, response.rowCount());
    }

    @Test
    public void testSelectStarEmptyMapping() throws Exception {
        prepareCreate("test")
                .execute().actionGet();
        execute("select * from test");
        assertArrayEquals(new String[]{}, response.cols());
        assertEquals(0, response.rowCount());
    }

    @Test
    public void testGroupByOnAnalyzedColumn() throws Exception {
        expectedException.expect(GroupByOnArrayUnsupportedException.class);

        execute("create table test1 (col1 string index using fulltext)");
        refresh();

        execute("select count(*) from test1 group by col1");
    }

    @Test
    public void testSelectStarWithOther() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "firstName", "type=string",
                        "lastName", "type=string")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id1").setRefresh(true)
                .setSource("{\"firstName\":\"Youri\",\"lastName\":\"Zoon\"}")
                .execute().actionGet();
        execute("select \"_version\", *, \"_id\" from test");
        assertArrayEquals(new String[]{"_version", "firstName", "lastName", "_id"},
                response.cols());
        assertEquals(1, response.rowCount());
        assertArrayEquals(new Object[]{1L, "Youri", "Zoon", "id1"}, response.rows()[0]);
    }

    @Test
    public void testSelectWithParams() throws Exception {
        prepareCreate("test")
            .addMapping("default",
                "first_name", "type=string,index=not_analyzed",
                "last_name", "type=string,index=not_analyzed",
                "age", "type=double,index=not_analyzed")
            .execute().actionGet();
        client().prepareIndex("test", "default", "id1").setRefresh(true)
            .setSource("{\"first_name\":\"Youri\",\"last_name\":\"Zoon\", \"age\": 38}")
            .execute().actionGet();

        Object[] args = new Object[] {"id1"};
        execute("select first_name, last_name from test where \"_id\" = $1", args);
        assertArrayEquals(new Object[]{"Youri", "Zoon"}, response.rows()[0]);

        args = new Object[] {"Zoon"};
        execute("select first_name, last_name from test where last_name = $1", args);
        assertArrayEquals(new Object[]{"Youri", "Zoon"}, response.rows()[0]);

        args = new Object[] {38, "Zoon"};
        execute("select first_name, last_name from test where age = $1 and last_name = $2", args);
        assertArrayEquals(new Object[]{"Youri", "Zoon"}, response.rows()[0]);

        args = new Object[] {38, "Zoon"};
        execute("select first_name, last_name from test where age = ? and last_name = ?", args);
        assertArrayEquals(new Object[]{"Youri", "Zoon"}, response.rows()[0]);
    }

    @Test
    public void testSelectStarWithOtherAndAlias() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "firstName", "type=string",
                        "lastName", "type=string")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id1").setRefresh(true)
                .setSource("{\"firstName\":\"Youri\",\"lastName\":\"Zoon\"}")
                .execute().actionGet();
        execute("select *, \"_version\", \"_version\" as v from test");
        assertArrayEquals(new String[]{"firstName", "lastName", "_version", "v"},
                response.cols());
        assertEquals(1, response.rowCount());
        assertArrayEquals(new Object[]{"Youri", "Zoon", 1L, 1L}, response.rows()[0]);
    }


    @Test
    public void testSelectNestedColumns() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "message", "type=string",
                        "person", "type=object")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id1").setRefresh(true)
                .setSource("{\"message\":\"I'm addicted to kite\", " +
                        "\"person\": { \"name\": \"Youri\", \"addresses\": [ { \"city\": " +
                        "\"Dirksland\", \"country\": \"NL\" } ] }}")
                .execute().actionGet();

        execute("select message, person['name'], person['addresses']['city'] from test " +
                "where person['name'] = 'Youri'");

        assertArrayEquals(new String[]{"message", "person['name']", "person['addresses']['city']"},
                response.cols());
        assertEquals(1, response.rowCount());
        assertArrayEquals(new Object[]{"I'm addicted to kite", "Youri",
                new ArrayList<String>(){{add("Dirksland");}}},
                response.rows()[0]);
    }

    @Test
    public void testFilterByEmptyString() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "name", "type=string,index=not_analyzed")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id1").setRefresh(true)
                .setSource("{\"name\":\"\"}")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id2").setRefresh(true)
                .setSource("{\"name\":\"Ruben Lenten\"}")
                .execute().actionGet();

        execute("select name from test where name = ''");
        assertEquals(1, response.rowCount());
        assertEquals("", response.rows()[0][0]);

        execute("select name from test where name != ''");
        assertEquals(1, response.rowCount());
        assertEquals("Ruben Lenten", response.rows()[0][0]);

    }

    @Test
    public void testFilterByNull() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "name", "type=string,index=not_analyzed")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id1").setRefresh(true)
                .setSource("{}")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id2").setRefresh(true)
                .setSource("{\"name\":\"Ruben Lenten\"}")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id3").setRefresh(true)
                .setSource("{\"name\":\"\"}")
                .execute().actionGet();

        execute("select \"_id\" from test where name is null");
        assertEquals(1, response.rowCount());
        assertEquals("id1", response.rows()[0][0]);

        execute("select \"_id\" from test where name is not null order by \"_uid\"");
        assertEquals(2, response.rowCount());
        assertEquals("id2", response.rows()[0][0]);

        // missing field is null
        execute("select \"_id\" from test where invalid is null");
        assertEquals(3, response.rowCount());

        execute("select name from test where name is not null and name!=''");
        assertEquals(1, response.rowCount());
        assertEquals("Ruben Lenten", response.rows()[0][0]);

    }

    @Test
    public void testFilterByBoolean() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "sunshine", "type=boolean,index=not_analyzed")
                .execute().actionGet();

        execute("insert into test values (?)", new Object[] {true});
        refresh();

        execute("select sunshine from test where sunshine = true");
        assertEquals(1, response.rowCount());
        assertEquals(true, response.rows()[0][0]);

        execute("update test set sunshine=false where sunshine = true");
        assertEquals(1, response.rowCount());
        refresh();

        execute("select sunshine from test where sunshine = ?", new Object[]{false});
        assertEquals(1, response.rowCount());
        assertEquals(false, response.rows()[0][0]);
    }


    /**
     * Queries are case sensitive by default, however column names without quotes are converted
     * to lowercase which is the same behaviour as in postgres
     * see also http://www.thenextage.com/wordpress/postgresql-case-sensitivity-part-1-the-ddl/
     *
     * @throws Exception
     */
    @Test
    public void testColsAreCaseSensitive() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "firstName", "type=string,store=true,index=not_analyzed",
                        "firstname", "type=string,store=true,index=not_analyzed")
                .execute().actionGet();

        client().prepareIndex("test", "default", "id1").setRefresh(true)
                .setSource("{\"firstname\":\"LowerCase\",\"firstName\":\"CamelCase\"}")
                .execute().actionGet();

        execute(
                "select FIRSTNAME, \"firstname\", \"firstName\" from test");
        assertArrayEquals(new String[]{"firstname", "firstname", "firstName"}, response.cols());
        assertEquals(1, response.rowCount());
        assertEquals("LowerCase", response.rows()[0][0]);
        assertEquals("LowerCase", response.rows()[0][1]);
        assertEquals("CamelCase", response.rows()[0][2]);
    }


    @Test
    public void testIdSelectWithResult() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        refresh();
        execute("select \"_id\" from test");
        assertArrayEquals(new String[]{"_id"}, response.cols());
        assertEquals(1, response.rowCount());
        assertEquals(1, response.rows()[0].length);
        assertEquals("id1", response.rows()[0][0]);
    }

    @Test
    public void testDelete() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        refresh();
        execute("delete from test");
        assertEquals(-1, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
        execute("select \"_id\" from test");
        assertEquals(0, response.rowCount());
    }

    @Test
    public void testDeleteWithWhere() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id2").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id3").setSource("{}").execute().actionGet();
        refresh();
        execute("delete from test where \"_id\" = 'id1'");
        assertEquals(1, response.rowCount());
        refresh();
        execute("select \"_id\" from test");
        assertEquals(2, response.rowCount());
    }

    @Test
    public void testSelectSource() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{\"a\":1}")
                .execute().actionGet();
        refresh();
        execute("select \"_source\" from test");
        assertArrayEquals(new String[]{"_source"}, response.cols());
        assertEquals(1, response.rowCount());
        assertEquals(1, response.rows()[0].length);
        assertEquals(1, (long) ((Map<String, Integer>) response.rows()[0][0]).get("a"));
    }

    @Test
    public void testSelectObject() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1")
                .setSource("{\"a\":{\"nested\":2}}")
                .execute().actionGet();
        refresh();

        execute("select a from test");
        assertArrayEquals(new String[]{"a"}, response.cols());
        assertEquals(1, response.rowCount());
        assertEquals(1, response.rows()[0].length);
        assertEquals(2, (long) ((Map<String, Integer>) response.rows()[0][0]).get("nested"));
    }


    @Test
    public void testSqlRequestWithLimit() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id2").setSource("{}").execute().actionGet();
        refresh();
        execute("select \"_id\" from test limit 1");
        assertEquals(1, response.rowCount());
    }


    @Test
    public void testSqlRequestWithLimitAndOffset() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id2").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id3").setSource("{}").execute().actionGet();
        refresh();
        execute("select \"_id\" from test limit 1 offset 1");
        assertEquals(1, response.rowCount());
    }


    @Test
    public void testSqlRequestWithFilter() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id2").setSource("{}").execute().actionGet();
        refresh();
        execute("select \"_id\" from test where \"_id\"='id1'");
        assertEquals(1, response.rowCount());
        assertEquals("id1", response.rows()[0][0]);
    }

    @Test
    public void testSqlRequestWithNotEqual() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id2").setSource("{}").execute().actionGet();
        refresh();
        execute("select \"_id\" from test where \"_id\"!='id1'");
        assertEquals(1, response.rowCount());
        assertEquals("id2", response.rows()[0][0]);
    }


    @Test
    public void testSqlRequestWithOneOrFilter() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id2").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id3").setSource("{}").execute().actionGet();
        refresh();
        execute(
                "select \"_id\" from test where \"_id\"='id1' or \"_id\"='id3' order by " +
                        "\"_uid\"");
        assertEquals(2, response.rowCount());
        assertEquals("id1", response.rows()[0][0]);
        assertEquals("id3", response.rows()[1][0]);
    }

    @Test
    public void testSqlRequestWithOneMultipleOrFilter() throws Exception {
        createIndex("test");
        client().prepareIndex("test", "default", "id1").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id2").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id3").setSource("{}").execute().actionGet();
        client().prepareIndex("test", "default", "id4").setSource("{}").execute().actionGet();
        refresh();
        execute(
                "select \"_id\" from test where " +
                        "\"_id\"='id1' or \"_id\"='id2' or \"_id\"='id4' " +
                        "order by \"_uid\"");
        assertEquals(3, response.rowCount());
        System.out.println(Arrays.toString(response.rows()[0]));
        System.out.println(Arrays.toString(response.rows()[1]));
        System.out.println(Arrays.toString(response.rows()[2]));
        assertEquals("id1", response.rows()[0][0]);
        assertEquals("id2", response.rows()[1][0]);
        assertEquals("id4", response.rows()[2][0]);
    }

    @Test
    public void testSqlRequestWithDateFilter() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "date", "type=date")
                .execute().actionGet();

        client().prepareIndex("test", "default", "id1")
                .setSource("{\"date\": " +
                        new TimeStampSQLType().toXContent("2013-10-01") + "}")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id2")
                .setSource("{\"date\": " +
                        new TimeStampSQLType().toXContent("2013-10-02") + "}")
                .execute().actionGet();
        refresh();
        execute(
                "select date from test where date = '2013-10-01'");
        assertEquals(1, response.rowCount());
        assertEquals(1380585600000L, response.rows()[0][0]);
    }

    @Test
    public void testSqlRequestWithDateGtFilter() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "date", "type=date")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id1")
                .setSource("{\"date\": " +
                        new TimeStampSQLType().toXContent("2013-10-01") + "}")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id2")
                .setSource("{\"date\":" +
                        new TimeStampSQLType().toXContent("2013-10-02") + "}")
                .execute().actionGet();
        refresh();
        execute(
                "select date from test where date > '2013-10-01'");
        assertEquals(1, response.rowCount());
        assertEquals(1380672000000L, response.rows()[0][0]);
    }

    @Test
    public void testSqlRequestWithNumericGtFilter() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "i", "type=long")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id1")
                .setSource("{\"i\":10}")
                .execute().actionGet();
        client().prepareIndex("test", "default", "id2")
                .setSource("{\"i\":20}")
                .execute().actionGet();
        refresh();
        execute(
                "select i from test where i > 10");
        assertEquals(1, response.rowCount());
        assertEquals(20, response.rows()[0][0]);
    }


    @Test
    public void testInsertWithColumnNames() throws Exception {
//            ESLogger logger = Loggers.getLogger("org.elasticsearch.org.cratedb");
//            logger.setLevel("DEBUG");
        prepareCreate("test")
                .addMapping("default",
                        "firstName", "type=string,store=true,index=not_analyzed",
                        "lastName", "type=string,store=true,index=not_analyzed")
                .execute().actionGet();

        execute("insert into test (\"firstName\", \"lastName\") values('Youri', 'Zoon')");
        assertEquals(1, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
        refresh();

        execute("select * from test where \"firstName\" = 'Youri'");

        assertEquals(1, response.rowCount());
        assertEquals("Youri", response.rows()[0][0]);
        assertEquals("Zoon", response.rows()[0][1]);
    }

    @Test
    public void testInsertWithoutColumnNames() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "firstName", "type=string,store=true,index=not_analyzed",
                        "lastName", "type=string,store=true,index=not_analyzed")
                .execute().actionGet();

        execute("insert into test values('Youri', 'Zoon')");
        assertEquals(1, response.rowCount());
        refresh();

        execute("select * from test where \"firstName\" = 'Youri'");

        assertEquals(1, response.rowCount());
        assertEquals("Youri", response.rows()[0][0]);
        assertEquals("Zoon", response.rows()[0][1]);
    }

    @Test
    public void testInsertAllCoreDatatypes() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "boolean", "type=boolean",
                        "datetime", "type=date",
                        "double", "type=double",
                        "float", "type=float",
                        "integer", "type=integer",
                        "long", "type=long",
                        "short", "type=short",
                        "string", "type=string,index=not_analyzed")
                .execute().actionGet();

        execute("insert into test values(true, '2013-09-10T21:51:43', 1.79769313486231570e+308, 3.402, 2147483647, 9223372036854775807, 32767, 'Youri')");
        execute("insert into test values(?, ?, ?, ?, ?, ?, ?, ?)",
            new Object[] { true, "2013-09-10T21:51:43", 1.79769313486231570e+308, 3.402, 2147483647, 9223372036854775807L, 32767, "Youri" });
        assertEquals(1, response.rowCount());
        refresh();

        execute("select * from test");

        assertEquals(2, response.rowCount());
        assertEquals(true, response.rows()[0][0]);
        assertEquals(1378849903000L, response.rows()[0][1]);
        assertEquals(1.79769313486231570e+308, response.rows()[0][2]);
        assertEquals(3.402, response.rows()[0][3]);
        assertEquals(2147483647, response.rows()[0][4]);
        assertEquals(9223372036854775807L, response.rows()[0][5]);
        assertEquals(32767, response.rows()[0][6]);
        assertEquals("Youri", response.rows()[0][7]);

        assertEquals(true, response.rows()[1][0]);
        assertEquals(1378849903000L, response.rows()[1][1]);
        assertEquals(1.79769313486231570e+308, response.rows()[1][2]);
        assertEquals(3.402, response.rows()[1][3]);
        assertEquals(2147483647, response.rows()[1][4]);
        assertEquals(9223372036854775807L, response.rows()[1][5]);
        assertEquals(32767, response.rows()[1][6]);
        assertEquals("Youri", response.rows()[1][7]);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInsertCoreTypesAsArrayAndObject() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder().startObject()
            .startObject("default")
                .startObject("properties")
                    .startObject("boolean").field("type", "boolean").endObject()
                    .startObject("craty")
                        .field("type", "object")
                        .startObject("properties")
                            .startObject("s").field("type", "string").endObject()
                            .startObject("d1").field("type", "date").endObject()
                            .startObject("d2").field("type", "date").endObject()
                        .endObject()
                    .endObject()
                    .startObject("datetime").field("type", "date").endObject()
                    .startObject("double").field("type", "double").endObject()
                    .startObject("float").field("type", "float").endObject()
                    .startObject("integer").field("type", "integer").endObject()
                    .startObject("long").field("type", "long").endObject()
                    .startObject("short").field("type", "short").endObject()
                    .startObject("string").field("type", "string").endObject()
                .endObject()
            .endObject()
            .endObject();
        prepareCreate("test")
            .addMapping("default", mapping)
            .execute().actionGet();

        Map<String, Object> craty = new HashMap<>();
        craty.put("s", new String[] { "foo", "bar"});
        craty.put("d1", new String[] { "2013-09-10T21:51:43", null});
        craty.put("d2", "2013-09-10T21:51:43");
        execute("insert into test values(?, ?, ?, ?, ?, ?, ?, ?, ?)",
            new Object[] {
                new Boolean[] {true, false},
                craty,
                new String[] {"2013-09-10T21:51:43", "2013-11-10T21:51:43"},
                new Double[] {1.79769313486231570e+308, 1.69769313486231570e+308},
                new Float[] {3.402f, 3.403f, null },
                new Integer[] {2147483647, 234583},
                new Long[] { 9223372036854775807L, 4L },
                new Short[] {32767, 2 },
                new String[] {"Youri", "Juri"}
            }
        );
        refresh();

        execute("select * from test");
        assertEquals(true, ((List<Boolean>)response.rows()[0][0]).get(0));
        assertEquals(false, ((List<Boolean>)response.rows()[0][0]).get(1));

        assertEquals("foo",
            ((List<String>)((Map<String, Object>)response.rows()[0][1]).get("s")).get(0));
        assertEquals("bar",
            ((List<String>)((Map<String, Object>)response.rows()[0][1]).get("s")).get(1));
        assertThat(
            ((List<Long>)((Map<String, Object>)response.rows()[0][1]).get("d1")).get(0),
            is(1378849903000L)
        );
        assertThat(
            ((List<Long>)((Map<String, Object>)response.rows()[0][1]).get("d1")).get(1),
            nullValue()
        );
        assertThat(
            (Long)((Map<String, Object>)response.rows()[0][1]).get("d2"),
            is(1378849903000L)
        );

        assertThat( ((List<Long>)response.rows()[0][2]).get(0), is(1378849903000L));
        assertThat( ((List<Long>)response.rows()[0][2]).get(1), is(1384120303000L));

        assertThat( ((List<Double>)response.rows()[0][3]).get(0), is(1.79769313486231570e+308));
        assertThat( ((List<Double>)response.rows()[0][3]).get(1), is(1.69769313486231570e+308));

        assertThat( ((List<Double>)response.rows()[0][4]).get(0), is(3.402));
        assertThat( ((List<Double>)response.rows()[0][4]).get(1), is(3.403));
        assertThat( ((List<Double>)response.rows()[0][4]).get(2), nullValue());

        assertThat( ((List<Integer>)response.rows()[0][5]).get(0), is(2147483647));
        assertThat( ((List<Integer>)response.rows()[0][5]).get(1), is(234583));

        assertThat( ((List<Long>)response.rows()[0][6]).get(0), is(9223372036854775807L));
        assertThat( ((List<Integer>)response.rows()[0][6]).get(1), is(4));

        assertThat( ((List<Integer>)response.rows()[0][7]).get(0), is(32767));
        assertThat( ((List<Integer>)response.rows()[0][7]).get(1), is(2));

        assertThat( ((List<String>)response.rows()[0][8]).get(0), is("Youri"));
        assertThat( ((List<String>)response.rows()[0][8]).get(1), is("Juri"));
    }

    @Test
    public void testInsertMultipleRows() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "age", "type=integer",
                        "name", "type=string,store=true,index=not_analyzed")
                .execute().actionGet();

        execute("insert into test values(32, 'Youri'), (42, 'Ruben')");
        assertEquals(2, response.rowCount());
        refresh();

        execute("select * from test order by \"name\"");

        assertEquals(2, response.rowCount());
        assertArrayEquals(new Object[]{42, "Ruben"}, response.rows()[0]);
        assertArrayEquals(new Object[]{32, "Youri"}, response.rows()[1]);
    }

    @Test
    public void testInsertWithParams() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "age", "type=integer",
                        "name", "type=string,store=true,index=not_analyzed")
                .execute().actionGet();

        Object[] args = new Object[] {32, "Youri"};
        execute("insert into test values(?, ?)", args);
        assertEquals(1, response.rowCount());
        refresh();

        execute("select * from test where name = 'Youri'");

        assertEquals(1, response.rowCount());
        assertEquals(32, response.rows()[0][0]);
        assertEquals("Youri", response.rows()[0][1]);
    }

    @Test
    public void testInsertMultipleRowsWithParams() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "age", "type=integer",
                        "name", "type=string,store=true,index=not_analyzed")
                .execute().actionGet();

        Object[] args = new Object[] {32, "Youri", 42, "Ruben"};
        execute("insert into test values(?, ?), (?, ?)", args);
        assertEquals(2, response.rowCount());
        refresh();

        execute("select * from test order by \"name\"");

        assertEquals(2, response.rowCount());
        assertArrayEquals(new Object[]{42, "Ruben"}, response.rows()[0]);
        assertArrayEquals(new Object[]{32, "Youri"}, response.rows()[1]);
    }

    @Test
    public void testInsertObject() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "message", "type=string,store=true,index=not_analyzed",
                        "person", "type=object,store=true")
                .execute().actionGet();

        Map<String, String> person = new HashMap<String, String>();
        person.put("first_name", "Youri");
        person.put("last_name", "Zoon");
        Object[] args = new Object[] {"I'm addicted to kite", person};

        execute("insert into test values(?, ?)", args);
        assertEquals(1, response.rowCount());
        refresh();

        execute("select * from test");

        assertEquals(1, response.rowCount());
        assertArrayEquals(args, response.rows()[0]);
    }

    @Test
    public void testUpdate() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "message", "type=string,index=not_analyzed")
                .execute().actionGet();

        execute("insert into test values('hello'),('again')");
        assertEquals(2, response.rowCount());
        refresh();

        execute("update test set message='b' where message = 'hello'");

        assertEquals(1, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
        refresh();

        execute("select message from test where message='b'");
        assertEquals(1, response.rowCount());
        assertEquals("b", response.rows()[0][0]);

    }

    @Test
    public void testUpdateMultipleDocuments() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "message", "type=string,index=not_analyzed")
                .execute().actionGet();

        execute("insert into test values('hello'),('again'),('hello')");
        assertEquals(3, response.rowCount());
        refresh();

        execute("update test set message='b' where message = 'hello'");

        assertEquals(2, response.rowCount());
        refresh();

        execute("select message from test where message='b'");
        assertEquals(2, response.rowCount());
        assertEquals("b", response.rows()[0][0]);

    }

    @Test
    public void testTwoColumnUpdate() throws Exception {
        prepareCreate("test")
            .addMapping("default",
                "col1", "type=string,index=not_analyzed",
                "col2", "type=string,index=not_analyzed")
            .execute().actionGet();

        execute("insert into test values('hello', 'hallo'), ('again', 'nochmal')");
        assertEquals(2, response.rowCount());
        refresh();

        execute("update test set col1='b' where col1 = 'hello'");

        assertEquals(1, response.rowCount());
        refresh();

        execute("select col1, col2 from test where col1='b'");
        assertEquals(1, response.rowCount());
        assertEquals("b", response.rows()[0][0]);
        assertEquals("hallo", response.rows()[0][1]);

    }

    @Test
    public void testUpdateWithArgs() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "coolness", "type=float,index=not_analyzed")
                .execute().actionGet();

        execute("insert into test values(1.1),(2.2)");
        assertEquals(2, response.rowCount());
        refresh();

        execute("update test set coolness=3.3 where coolness = ?", new Object[]{2.2});

        assertEquals(1, response.rowCount());
        refresh();

        execute("select coolness from test where coolness=3.3");
        assertEquals(1, response.rowCount());
        assertEquals(3.3, response.rows()[0][0]);

    }

    @Test
    public void testUpdateNestedObjectWithoutDetailedSchema() throws Exception {
        prepareCreate("test")
            .addMapping("default",
                "coolness", "type=object,index=not_analyzed")
            .execute().actionGet();
        ensureGreen();

        Map<String, Object> map = new HashMap<>();
        map.put("x", "1");
        map.put("y", 2);
        Object[] args = new Object[] { map };

        execute("insert into test values (?)", args);
        assertEquals(1, response.rowCount());
        refresh();

        execute("update test set coolness['x'] = '3'");

        assertEquals(1, response.rowCount());
        refresh();

        execute("select coolness['x'], coolness['y'] from test");
        assertEquals(1, response.rowCount());
        assertEquals("3", response.rows()[0][0]);
        assertEquals(2, response.rows()[0][1]);
    }

    @Test
    public void testUpdateNestedNestedObject() throws Exception {
        Settings settings = settingsBuilder()
            .put("mapper.dynamic", true)
            .put("number_of_replicas", 0)
            .build();
        prepareCreate("test")
            .setSettings(settings)
            .execute().actionGet();
        ensureGreen();

        Map<String, Object> map = new HashMap<>();
        map.put("x", "1");
        map.put("y", 2);
        Object[] args = new Object[] { map };

        execute("insert into test (a) values (?)", args);
        refresh();

        execute("update test set coolness['x']['y']['z'] = 3");

        assertEquals(1, response.rowCount());
        refresh();

        execute("select coolness['x'], a from test");
        assertEquals(1, response.rowCount());
        assertEquals("{y={z=3}}", response.rows()[0][0].toString());
        assertEquals(map, response.rows()[0][1]);

        execute("update test set firstcol = 1, coolness['x']['a'] = 'a', coolness['x']['b'] = 'b', othercol = 2");
        assertEquals(1, response.rowCount());
        refresh();

        execute("select coolness['x']['b'], coolness['x']['a'], coolness['x']['y']['z'], " +
                "firstcol, othercol from test");
        assertEquals(1, response.rowCount());
        Object[] firstRow = response.rows()[0];
        assertEquals("b", firstRow[0]);
        assertEquals("a", firstRow[1]);
        assertEquals(3, firstRow[2]);
        assertEquals(1, firstRow[3]);
        assertEquals(2, firstRow[4]);
    }

    @Test
    public void testUpdateNestedObjectDeleteWithArgs() throws Exception {
        Settings settings = settingsBuilder()
            .put("mapper.dynamic", true)
            .put("number_of_replicas", 0)
            .build();
        prepareCreate("test")
            .setSettings(settings)
            .execute().actionGet();
        ensureGreen();

        Map<String, Object> map = newHashMap();
        Map<String, Object> nestedMap = newHashMap();
        nestedMap.put("y", 2);
        nestedMap.put("z", 3);
        map.put("x", nestedMap);
        Object[] args = new Object[] { map };

        execute("insert into test (a) values (?)", args);
        assertEquals(1, response.rowCount());
        refresh();

        execute("update test set a['x']['z'] = ?", new Object[] { null });

        assertEquals(1, response.rowCount());
        refresh();

        execute("select a['x']['y'], a['x']['z'] from test");
        assertEquals(1, response.rowCount());
        assertEquals(2, response.rows()[0][0]);
        assertNull(response.rows()[0][1]);
    }

    @Test
    public void testUpdateNestedObjectDeleteWithoutArgs() throws Exception {
        Settings settings = settingsBuilder()
            .put("mapper.dynamic", true)
            .put("number_of_replicas", 0)
            .build();
        prepareCreate("test")
            .setSettings(settings)
            .execute().actionGet();
        ensureGreen();

        Map<String, Object> map = newHashMap();
        Map<String, Object> nestedMap = newHashMap();
        nestedMap.put("y", 2);
        nestedMap.put("z", 3);
        map.put("x", nestedMap);
        Object[] args = new Object[] { map };

        execute("insert into test (a) values (?)", args);
        assertEquals(1, response.rowCount());
        refresh();

        execute("update test set a['x']['z'] = null");

        assertEquals(1, response.rowCount());
        refresh();

        execute("select a['x']['z'], a['x']['y'] from test");
        assertEquals(1, response.rowCount());
        assertNull(response.rows()[0][0]);
        assertEquals(2, response.rows()[0][1]);
    }

    @Test(expected = SQLParseException.class)
    public void testUpdateWithNestedObjectArrayIdxAccess() throws Exception {
        prepareCreate("test")
            .addMapping("default",
                    "coolness", "type=float,index=not_analyzed")
            .execute().actionGet();

        execute("insert into test values (?)", new Object[]{new Object[]{2.2, 2.3, 2.4}});
        assertEquals(1, response.rowCount());
        refresh();

        execute("update test set coolness[0] = 3.3");
    }

    @Test
    public void testUpdateNestedObjectWithDetailedSchema() throws Exception {
        prepareCreate("test")
            .addMapping("default", "{\n" +
                "    \"type\": {\n" +
                "        \"properties\": {\n" +
                "            \"coolness\": {\n" +
                "                \"type\": \"object\",\n" +
                "                \"properties\": {\n" +
                "                    \"x\": {\n" +
                "                        \"type\": \"string\",\n" +
                "                        \"index\": \"not_analyzed\"\n" +
                "                    },\n" +
                "                    \"y\": {\n" +
                "                        \"type\": \"string\",\n" +
                "                        \"index\": \"not_analyzed\"\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n")
            .execute().actionGet();

        Map<String, Object> map = new HashMap<String, Object>();
        map.put("x", "1");
        map.put("y", "2");
        Object[] args = new Object[] { map };

        execute("insert into test values (?)", args);
        assertEquals(1, response.rowCount());
        refresh();

        execute("update test set coolness['x'] = '3'");

        assertEquals(1, response.rowCount());
        refresh();

        execute("select coolness from test");
        assertEquals(1, response.rowCount());
        assertEquals("{y=2, x=3}", response.rows()[0][0].toString());
    }

    @Test
    public void testUpdateResetNestedObject() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "coolness", "type=object,index=not_analyzed")
                .execute().actionGet();
        ensureGreen();

        Map<String, Object> map = new HashMap<>();
        map.put("x", "1");
        map.put("y", 2);
        Object[] args = new Object[] { map };

        execute("insert into test values (?)", args);
        assertEquals(1, response.rowCount());
        refresh();

        // update with different map
        Map<String, Object> new_map = new HashMap<>();
        new_map.put("z", 1);

        execute("update test set coolness = ?", new Object[]{new_map});
        assertEquals(1, response.rowCount());
        refresh();

        execute("select coolness from test");
        assertEquals(1, response.rowCount());
        assertEquals(new_map, response.rows()[0][0]);

        // update with empty map
        Map<String, Object> empty_map = new HashMap<>();

        execute("update test set coolness = ?", new Object[]{empty_map});
        assertEquals(1, response.rowCount());
        refresh();

        execute("select coolness from test");
        assertEquals(1, response.rowCount());
        assertEquals(empty_map, response.rows()[0][0]);
    }

    @Test
    public void testUpdateResetNestedObjectUsingUpdateRequest() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder().startObject()
                .startObject("default")
                    .startObject("_meta").field("primary_keys", "id").endObject()
                    .startObject("properties")
                        .startObject("id")
                            .field("type", "string")
                            .field("index", "not_analyzed")
                        .endObject()
                        .startObject("data")
                            .field("type", "object")
                            .field("index", "not_analyzed")
                            .field("dynamic", false)
                        .endObject()
                    .endObject()
                .endObject()
                .endObject();

        prepareCreate("test")
                .addMapping("default", mapping)
                .execute().actionGet();
        ensureGreen();

        Map<String, Object> data = new HashMap<String, Object>(){{
            put("foo", "bar");
            put("days", new ArrayList<String>(){{
                add("Mon");
                add("Tue");
                add("Wen");
            }});
        }};
        execute("insert into test (id, data) values (?, ?)", new Object[] { "1", data});
        refresh();

        execute("select data from test where id = ?", new Object[]{"1"});
        assertEquals(data, response.rows()[0][0]);

        Map<String, Object> new_data = new HashMap<String, Object>(){{
            put("days", new ArrayList<String>(){{
                add("Mon");
                add("Wen");
            }});
        }};
        execute("update test set data = ? where id = ?", new Object[]{new_data, "1"});
        assertEquals(1, response.rowCount());
        refresh();

        execute("select data from test where id = ?", new Object[] { "1" });
        assertEquals(new_data, response.rows()[0][0]);
    }

    @Test
    public void testGetResponseWithObjectColumn() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder().startObject()
            .startObject("default")
                .startObject("_meta").field("primary_keys", "id").endObject()
                .startObject("properties")
                    .startObject("id")
                        .field("type", "string")
                        .field("index", "not_analyzed")
                    .endObject()
                .startObject("data")
                    .field("type", "object")
                    .field("index", "not_analyzed")
                    .field("dynamic", false)
                .endObject()
            .endObject()
            .endObject()
            .endObject();

        prepareCreate("test")
            .addMapping("default", mapping)
            .execute().actionGet();
        ensureGreen();

        Map<String, Object> data = new HashMap<>();
        data.put("foo", "bar");
        execute("insert into test (id, data) values (?, ?)", new Object[]{"1", data});
        refresh();

        execute("select data from test where id = ?", new Object[]{"1"});
        assertEquals(data, response.rows()[0][0]);
    }

    @Test
    public void testUpdateResetNestedNestedObject() throws Exception {
        prepareCreate("test")
                .addMapping("default",
                        "coolness", "type=object,index=not_analyzed")
                .execute().actionGet();
        ensureGreen();

        Map<String, Object> map = new HashMap<String, Object>(){{
            put("x", "1");
            put("y", new HashMap<String, Object>(){{
                put("z", 3);
            }});
        }};

        execute("insert into test values (?)", new Object[]{map});
        assertEquals(1, response.rowCount());
        refresh();

        Map<String, Object> new_map = new HashMap<>();
        new_map.put("a", 1);

        execute("update test set coolness['y'] = ?", new Object[]{new_map});
        assertEquals(1, response.rowCount());
        refresh();

        execute("select coolness['y'], coolness['x'] from test");
        assertEquals(1, response.rowCount());
        assertEquals(new_map, response.rows()[0][0]);
        assertEquals("1", response.rows()[0][1]);
    }

    @Test
    public void testInsertWithPrimaryKey() throws Exception {
        createTestIndexWithPkMapping();

        Object[] args = new Object[] { "1",
            "A towel is about the most massively useful thing an interstellar hitch hiker can have."};
        execute("insert into test (pk_col, message) values (?, ?)", args);
        refresh();

        GetResponse response = client().prepareGet("test", "default", "1").execute().actionGet();
        assertTrue(response.getSourceAsMap().containsKey("message"));
    }

    @Test
    public void testInsertWithPrimaryKeyMultiValues() throws Exception {
        createTestIndexWithPkMapping();

        Object[] args = new Object[] {
            "1", "All the doors in this spaceship have a cheerful and sunny disposition.",
            "2", "I always thought something was fundamentally wrong with the universe"
        };
        execute("insert into test (pk_col, message) values (?, ?), (?, ?)", args);
        refresh();

        GetResponse response = client().prepareGet("test", "default", "1").execute().actionGet();
        assertTrue(response.getSourceAsMap().containsKey("message"));
    }

    @Test (expected = DuplicateKeyException.class)
    public void testInsertWithUniqueContraintViolation() throws Exception {
        createTestIndexWithPkMapping();

        Object[] args = new Object[] {
            "1", "All the doors in this spaceship have a cheerful and sunny disposition.",
        };
        execute("insert into test (pk_col, message) values (?, ?)", args);

        args = new Object[] {
            "1", "I always thought something was fundamentally wrong with the universe"
        };

        execute("insert into test (pk_col, message) values (?, ?)", args);
    }

    private void createTestIndexWithPkMapping() throws IOException {
        XContentBuilder mapping = XContentFactory.jsonBuilder().startObject()
                    .startObject("default")
                    .startObject("_meta").field("primary_keys", "pk_col").endObject()
                    .startObject("properties")
                    .startObject("pk_col").field("type", "string").field("store",
                            "true").field("index", "not_analyzed").endObject()
                    .startObject("message").field("type", "string").field("store",
                            "true").field("index", "not_analyzed").endObject()
                    .endObject()
                    .endObject()
                    .endObject();

        prepareCreate("test")
            .addMapping("default", mapping)
                .execute().actionGet();
        ensureGreen();
    }

    @Test (expected = SQLParseException.class)
    public void testMultiplePrimaryKeyColumns() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder().startObject()
                    .startObject("default")
                    .startObject("_meta").array("primary_keys", "pk_col1", "pk_col2").endObject()
                    .startObject("properties")
                    .startObject("pk_col").field("type", "string").field("store",
                            "true").field("index", "not_analyzed").endObject()
                    .startObject("message").field("type", "string").field("store",
                            "true").field("index", "not_analyzed").endObject()
                    .endObject()
                    .endObject()
                    .endObject();

        prepareCreate("test")
            .addMapping("default", mapping)
            .execute().actionGet();

        Object[] args = new Object[] {
            "Life, loathe it or ignore it, you can't like it."
        };
        execute("insert into test (message) values (?)", args);
    }

    @Test (expected = SQLParseException.class)
    public void testInsertWithPKMissingOnInsert() throws Exception {
        createTestIndexWithPkMapping();

        Object[] args = new Object[] {
            "In the beginning the Universe was created.\n" +
                "This has made a lot of people very angry and been widely regarded as a bad move."
        };
        execute("insert into test (message) values (?)", args);
    }

    private void createTestIndexWithSomeIdPkMapping() throws IOException {
        XContentBuilder mapping = XContentFactory.jsonBuilder().startObject()
                .startObject("default")
                .startObject("_meta").field("primary_keys", "some_id").endObject()
                .startObject("properties")
                .startObject("some_id").field("type", "string").field("store",
                        "true").field("index", "not_analyzed").endObject()
                .startObject("foo").field("type", "string").field("store",
                        "true").field("index", "not_analyzed").endObject()
                .endObject()
                .endObject()
                .endObject();

        prepareCreate("test")
                .addMapping("default", mapping)
                .execute().actionGet();
        ensureGreen();
    }

    @Test
    public void testSelectToGetRequestByPlanner() throws Exception {
        createTestIndexWithSomeIdPkMapping();

        execute("insert into test (some_id, foo) values ('124', 'bar1')");
        assertEquals(1, response.rowCount());
        refresh();

        execute("select some_id, foo from test where some_id='124'");
        assertEquals(1, response.rowCount());
        assertEquals("124", response.rows()[0][0]);
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
    }


    @Test
    public void testDeleteToDeleteRequestByPlanner() throws Exception {
        createTestIndexWithSomeIdPkMapping();

        execute("insert into test (some_id, foo) values ('123', 'bar')");
        assertEquals(1, response.rowCount());
        refresh();

        execute("delete from test where some_id='123'");
        assertEquals(1, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
        refresh();

        execute("select * from test where some_id='123'");
        assertEquals(0, response.rowCount());
    }

    @Test
    public void testUpdateToUpdateRequestByPlanner() throws Exception {
        createTestIndexWithSomeIdPkMapping();

        execute("insert into test (some_id, foo) values ('123', 'bar')");
        assertEquals(1, response.rowCount());
        refresh();

        execute("update test set foo='bar1' where some_id='123'");
        assertEquals(1, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
        refresh();

        execute("select foo from test where some_id='123'");
        assertEquals(1, response.rowCount());
        assertEquals("bar1", response.rows()[0][0]);
    }

    @Test
    public void testSelectToRoutedRequestByPlanner() throws Exception {
        createTestIndexWithSomeIdPkMapping();

        execute("insert into test (some_id, foo) values ('1', 'foo')");
        execute("insert into test (some_id, foo) values ('2', 'bar')");
        execute("insert into test (some_id, foo) values ('3', 'baz')");
        refresh();

        execute("SELECT * FROM test WHERE some_id='1' OR some_id='2'");
        assertEquals(2, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));

        execute("SELECT * FROM test WHERE some_id=? OR some_id=?", new Object[]{"1", "2"});
        assertEquals(2, response.rowCount());

        execute("SELECT * FROM test WHERE (some_id=? OR some_id=?) OR some_id=?", new Object[]{"1", "2", "3"});
        assertEquals(3, response.rowCount());
        assertThat(Arrays.asList(response.cols()), hasItems("some_id", "foo"));
    }

    @Test
    public void testSelectToRoutedRequestByPlannerMissingDocuments() throws Exception {
        createTestIndexWithSomeIdPkMapping();

        execute("insert into test (some_id, foo) values ('1', 'foo')");
        execute("insert into test (some_id, foo) values ('2', 'bar')");
        execute("insert into test (some_id, foo) values ('3', 'baz')");
        refresh();

        execute("SELECT some_id, foo FROM test WHERE some_id='4' OR some_id='3'");
        assertEquals(1, response.rowCount());
        assertThat(Arrays.asList(response.rows()[0]), hasItems(new Object[]{"3", "baz"}));
        assertThat(response.duration(), greaterThanOrEqualTo(0L));

        execute("SELECT some_id, foo FROM test WHERE some_id='4' OR some_id='99'");
        assertEquals(0, response.rowCount());
    }

    @Test
    public void testSelectToRoutedRequestByPlannerWhereIn() throws Exception {
        createTestIndexWithSomeIdPkMapping();

        execute("insert into test (some_id, foo) values ('1', 'foo')");
        execute("insert into test (some_id, foo) values ('2', 'bar')");
        execute("insert into test (some_id, foo) values ('3', 'baz')");
        refresh();

        execute("SELECT * FROM test WHERE some_id IN (?,?,?)", new Object[]{"1", "2", "3"});
        assertEquals(3, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
    }

    @Test
    public void testDeleteToRoutedRequestByPlannerWhereIn() throws Exception {
        createTestIndexWithSomeIdPkMapping();

        execute("insert into test (some_id, foo) values ('1', 'foo')");
        execute("insert into test (some_id, foo) values ('2', 'bar')");
        execute("insert into test (some_id, foo) values ('3', 'baz')");
        refresh();

        execute("DELETE FROM test WHERE some_Id IN (?, ?, ?)", new Object[]{"1", "2", "4"});
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
        refresh();

        execute("SELECT some_id FROM test");
        assertThat(response.rowCount(), is(1L));
        assertEquals(response.rows()[0][0], "3");

    }

    @Test
    public void testCountWithGroupByNullArgs() throws Exception {
        groupBySetup();

        execute("select count(*), race from characters group by race", null);
        assertEquals(3, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
    }


    @Test
    public void testCountWithGroupBy() throws Exception {

        groupBySetup();

        execute("select count(*), race from characters group by race");
        assertEquals(3, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));

        List<Tuple<Long, String>> result = newArrayList();

        for (int i = 0; i < response.rows().length; i++) {
            result.add(new Tuple<>((Long)response.rows()[i][0], (String)response.rows()[i][1]));
        }

        Ordering ordering = Ordering.natural().onResultOf(
            new Function<Tuple<Long, String>, Comparable>() {

            @Override
            public Comparable apply(@Nullable Tuple<Long, String> input) {
                return input.v1();
            }
        });

        Collections.sort(result, ordering);
        assertEquals("Android", result.get(0).v2());
        assertThat(result.get(0).v1(), is(1L));
        assertEquals("Vogon", result.get(1).v2());
        assertThat(result.get(1).v1(), is(2L));
        assertEquals("Human", result.get(2).v2());
        assertThat(result.get(2).v1(), is(3L));
    }

    @Test
    public void testCountWithGroupByWithWhereClause() throws Exception {
        groupBySetup();

        execute("select count(*), race from characters where race = 'Human' group by race");
        assertEquals(1, response.rowCount());
    }

    @Test
    public void testCountWithGroupByOrderOnAggAscFuncAndLimit() throws Exception {
        groupBySetup();

        execute("select count(*), race from characters group by race order by count(*) asc limit 2");

        assertEquals(2, response.rowCount());
        assertEquals(1L, response.rows()[0][0]);
        assertEquals("Android", response.rows()[0][1]);
        assertEquals(2L, response.rows()[1][0]);
        assertEquals("Vogon", response.rows()[1][1]);
    }

    @Test
    public void testCountWithGroupByOrderOnAggAscFuncAndSecondColumnAndLimit() throws Exception {
        groupBySetup();

        execute("select count(*), gender, race from characters group by race, gender order by count(*) desc, race asc limit 2");

        assertEquals(2, response.rowCount());
        assertEquals(2L, response.rows()[0][0]);
        assertEquals("male", response.rows()[0][1]);
        assertEquals("Human", response.rows()[0][2]);
        assertEquals(2L, response.rows()[1][0]);
        assertEquals("male", response.rows()[1][1]);
        assertEquals("Vogon", response.rows()[1][2]);
    }

    @Test
    public void testCountWithGroupByOrderOnAggAscFuncAndSecondColumnAndLimitAndOffset() throws Exception {
        groupBySetup();

        execute("select count(*), gender, race from characters group by race, gender order by count(*) desc, race asc limit 2 offset 2");

        assertEquals(2, response.rowCount());
        assertEquals(1L, response.rows()[0][0]);
        assertEquals("male", response.rows()[0][1]);
        assertEquals("Android", response.rows()[0][2]);
        assertEquals(1L, response.rows()[1][0]);
        assertEquals("female", response.rows()[1][1]);
        assertEquals("Human", response.rows()[1][2]);
    }

    @Test
    public void testCountWithGroupByOrderOnAggAscFuncAndSecondColumnAndLimitAndTooLargeOffset() throws Exception {
        groupBySetup();

        execute("select count(*), gender, race from characters group by race, gender order by count(*) desc, race asc limit 2 offset 20");

        assertEquals(0, response.rows().length);
        assertEquals(0, response.rowCount());
    }

    @Test
    public void testCountWithGroupByOrderOnAggDescFuncAndLimit() throws Exception {
        groupBySetup();

        execute("select count(*), race from characters group by race order by count(*) desc limit 2");

        assertEquals(2, response.rowCount());
        assertEquals(3L, response.rows()[0][0]);
        assertEquals("Human", response.rows()[0][1]);
        assertEquals(2L, response.rows()[1][0]);
        assertEquals("Vogon", response.rows()[1][1]);
    }

    @Test
    public void testCountWithGroupByOrderOnKeyAscAndLimit() throws Exception {
        groupBySetup();


        execute("select count(*), race from characters group by race order by race asc limit 2");

        assertEquals(2, response.rowCount());
        assertEquals(1L, response.rows()[0][0]);
        assertEquals("Android", response.rows()[0][1]);
        assertEquals(3L, response.rows()[1][0]);
        assertEquals("Human", response.rows()[1][1]);
    }

    @Test
    public void testCountWithGroupByOrderOnKeyDescAndLimit() throws Exception {
        groupBySetup();

        execute("select count(*), race from characters group by race order by race desc limit 2");

        assertEquals(2, response.rowCount());
        assertEquals(2L, response.rows()[0][0]);
        assertEquals("Vogon", response.rows()[0][1]);
        assertEquals(3L, response.rows()[1][0]);
        assertEquals("Human", response.rows()[1][1]);
    }

    private void groupBySetup() throws Exception {
        XContentBuilder mapping = XContentFactory.jsonBuilder().startObject()
            .startObject("default")
            .startObject("properties")
                .startObject("race")
                    .field("type", "string")
                    .field("index", "not_analyzed")
                .endObject()
                .startObject("gender")
                    .field("type", "string")
                    .field("index", "not_analyzed")
                .endObject()
                .startObject("age")
                    .field("type", "integer")
                .endObject()
                .startObject("name")
                    .field("type", "string")
                    .field("index", "not_analyzed")
                .endObject()
                .startObject("details")
                    .field("type", "object")
                    .field("index", "not_analyzed")
                .endObject()
            .endObject()
            .endObject()
            .endObject();


        prepareCreate("characters")
                .addMapping("default", mapping)
                .execute().actionGet();
        ensureGreen();

        Map<String, String> details = newHashMap();
        details.put("job", "Sandwitch Maker");
        execute("insert into characters (race, gender, age, name, details) values (?, ?, ?, ?, ?)",
            new Object[] {"Human", "male", 34, "Arthur Dent", details});

        details = newHashMap();
        details.put("job", "Mathematician");
        execute("insert into characters (race, gender, age, name, details) values (?, ?, ?, ?, ?)",
            new Object[] {"Human", "female", 32, "Trillian", details});
        execute("insert into characters (race, gender, age, name) values (?, ?, ?, ?)",
            new Object[] {"Human", "male", 112, "Ford Perfect"});
        execute("insert into characters (race, gender, name) values ('Android', 'male', 'Marving')");
        execute("insert into characters (race, gender, name) values ('Vogon', 'male', 'Jeltz')");
        execute("insert into characters (race, gender, name) values ('Vogon', 'male', 'Kwaltz')");
        refresh();

        execute("select count(*) from characters");
        assertEquals(6L, response.rows()[0][0]);
    }

    @Test
    public void testSelectWithWhereLike() throws Exception {
        groupBySetup();

        execute("select name from characters where name like '%ltz'");
        assertEquals(2L, response.rowCount());

        execute("select count(*) from characters where name like 'Jeltz'");
        assertEquals(1L, response.rows()[0][0]);

        execute("select count(*) from characters where race like '%o%'");
        assertEquals(3L, response.rows()[0][0]);

        Map<String, Object> emptyMap = new HashMap<>();
        Map<String, Object> details = new HashMap<>();
        details.put("age", 30);
        details.put("job", "soldier");
        execute("insert into characters (race, gender, name, details) values (?, ?, ?, ?)",
            new Object[] {"Vo*", "male", "Kwaltzz", emptyMap }
        );
        execute("insert into characters (race, gender, name, details) values (?, ?, ?, ?)",
            new Object[] {"Vo?", "male", "Kwaltzzz", emptyMap }
        );
        execute("insert into characters (race, gender, name, details) values (?, ?, ?, ?)",
            new Object[] {"Vo!", "male", "Kwaltzzzz", details}
        );
        execute("insert into characters (race, gender, name, details) values (?, ?, ?, ?)",
            new Object[] {"Vo%", "male", "Kwaltzzzz", details}
        );
        refresh();

        execute("select race from characters where race like 'Vo*'");
        assertEquals(1L, response.rowCount());
        assertEquals("Vo*", response.rows()[0][0]);

        execute("select race from characters where race like ?", new Object[]{"Vo?"});
        assertEquals(1L, response.rowCount());
        assertEquals("Vo?", response.rows()[0][0]);

        execute("select race from characters where race like 'Vo!'");
        assertEquals(1L, response.rowCount());
        assertEquals("Vo!", response.rows()[0][0]);

        execute("select race from characters where race like 'Vo\\%'");
        assertEquals(1L, response.rowCount());
        assertEquals("Vo%", response.rows()[0][0]);

        execute("select race from characters where race like 'Vo_'");
        assertEquals(4L, response.rowCount());

        execute("select count(*) from characters where age like 32");
        assertEquals(1L, response.rows()[0][0]);

        execute("select race from characters where details['age'] like 30");
        assertEquals(2L, response.rowCount());

        execute("select race from characters where details['job'] like 'sol%'");
        assertEquals(2L, response.rowCount());
    }

    @Test
    public void testCreateTable() throws Exception {
        execute("create table test (col1 integer primary key, col2 string)");
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("test"))
                .actionGet().isExists());

        String expectedMapping = "{\"default\":{" +
                "\"_meta\":{\"primary_keys\":\"col1\"}," +
                "\"properties\":{" +
                    "\"col1\":{\"type\":\"integer\"}," +
                    "\"col2\":{\"type\":\"string\",\"index\":\"not_analyzed\"," +
                                "\"omit_norms\":true,\"index_options\":\"docs\"}" +
                "}}}";

        String expectedSettings = "{\"test\":{" +
                "\"settings\":{" +
                "\"index.number_of_replicas\":\"1\"," +
                "\"index.number_of_shards\":\"5\"," +
                "\"index.version.created\":\"900599\"" +
                "}}}";

        assertEquals(expectedMapping, getIndexMapping("test"));
        assertEquals(expectedSettings, getIndexSettings("test"));

        // test index usage
        execute("insert into test (col1, col2) values (1, 'foo')");
        assertEquals(1, response.rowCount());
        refresh();
        execute("SELECT * FROM test");
        assertEquals(1L, response.rowCount());
    }

    @Test
    public void testSqlAlchemyGeneratedCountWithStar() throws  Exception {
        // generated using sqlalchemy
        // session.query(func.count('*')).filter(Test.name == 'foo').scalar()

        execute("create table test (col1 integer primary key, col2 string)");
        execute("insert into test values (?, ?)", new Object[] { 1, "foo" });
        execute("insert into test values (?, ?)", new Object[]{2, "bar"});
        refresh();

        execute(
            "SELECT count(?) AS count_1 FROM test WHERE test.col2 = ?",
            new Object[] { "*", "foo" }
        );
        assertEquals(1L, response.rows()[0][0]);
    }

    @Test
    public void testSqlAlchemyGeneratedCountWithPrimaryKeyCol() throws Exception {
        // generated using sqlalchemy
        // session.query(Test.col1).filter(Test.col2 == 'foo').scalar()

        execute("create table test (col1 integer primary key, col2 string)");
        execute("insert into test values (?, ?)", new Object[] { 1, "foo" });
        execute("insert into test values (?, ?)", new Object[] { 2, "bar" });
        refresh();

        execute(
            "SELECT count(test.col1) AS count_1 FROM test WHERE test.col2 = ?",
            new Object[] { "foo" }
        );
        assertEquals(1L, response.rows()[0][0]);
    }

    @Test
    public void testSqlAlchemyGroupByWithCountStar() throws Exception {
        // generated using sqlalchemy
        // session.query(func.count('*'), Test.col2).group_by(Test.col2).order_by(desc(func.count('*'))).all()

        execute("create table test (col1 integer primary key, col2 string)");
        execute("insert into test values (?, ?)", new Object[] { 1, "foo" });
        execute("insert into test values (?, ?)", new Object[] { 2, "bar" });
        execute("insert into test values (?, ?)", new Object[]{3, "foo"});
        refresh();

        execute(
            "SELECT count(?) AS count_1, test.col2 AS test_col2 FROM test " +
                "GROUP BY test.col2 order by count(?) desc",
            new Object[] { "*", "*" }
        );

        assertEquals(2L, response.rows()[0][0]);
    }

    @Test
    public void testSqlAlchemyGroupByWithPrimaryKeyCol() throws Exception {
        // generated using sqlalchemy
        // session.query(func.count(Test.col1), Test.col2).group_by(Test.col2).order_by(desc(func.count(Test.col1))).all()


        execute("create table test (col1 integer primary key, col2 string)");
        execute("insert into test values (?, ?)", new Object[] { 1, "foo" });
        execute("insert into test values (?, ?)", new Object[] { 2, "bar" });
        execute("insert into test values (?, ?)", new Object[] { 3, "foo" });
        refresh();

        execute(
            "SELECT count(test.col1) AS count_1, test.col2 AS test_col2 FROM test " +
                "GROUP BY test.col2 order by count(test.col1) desc"
        );

        assertEquals(2L, response.rows()[0][0]);
    }

    @Test(expected = TableAlreadyExistsException.class)
    public void testCreateTableAlreadyExistsException() throws Exception {
        execute("create table test (col1 integer primary key, col2 string)");
        execute("create table test (col1 integer primary key, col2 string)");
    }

    @Test
    public void testCreateTableWithReplicasAndShards() throws Exception {
        execute("create table test (col1 integer primary key, col2 string) replicas 2" +
                "clustered by (col1) into 10 shards");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("test"))
                .actionGet().isExists());

        String expectedMapping = "{\"default\":{" +
                "\"_meta\":{\"primary_keys\":\"col1\"}," +
                "\"properties\":{" +
                "\"col1\":{\"type\":\"integer\"}," +
                "\"col2\":{\"type\":\"string\",\"index\":\"not_analyzed\"," +
                "\"omit_norms\":true,\"index_options\":\"docs\"}" +
                "}}}";

        String expectedSettings = "{\"test\":{" +
                "\"settings\":{" +
                    "\"index.number_of_replicas\":\"2\"," +
                    "\"index.number_of_shards\":\"10\"," +
                    "\"index.version.created\":\"900599\"" +
                "}}}";

        assertEquals(expectedMapping, getIndexMapping("test"));
        assertEquals(expectedSettings, getIndexSettings("test"));
    }

    @Test
    public void testGroupByNestedObject() throws Exception {
        groupBySetup();

        execute("select count(*), details['job'] from characters group by details['job'] order by count(*), details['job']");
        assertEquals(3, response.rowCount());
        assertEquals(1L, response.rows()[0][0]);
        assertEquals("Mathematician", response.rows()[0][1]);
        assertEquals(1L, response.rows()[1][0]);
        assertEquals("Sandwitch Maker", response.rows()[1][1]);
        assertEquals(4L, response.rows()[2][0]);
        assertNull(null, response.rows()[2][1]);
    }

    @Test
    public void testGroupByEmpty() throws Exception {
        execute("create table test (col1 string)");
        refresh();

        execute("select count(*), col1 from test group by col1");
        assertEquals(0, response.rowCount());
    }

    @Test
    public void testGroupByMultiValueField() throws Exception {
        expectedException.expect(GroupByOnArrayUnsupportedException.class);
        groupBySetup();

        execute("insert into characters (race, gender, name) values (?, ?, ?)",
            new Object[] { new String[] {"Android"}, new String[] {"male", "robot"}, "Marvin2"}
        );
        execute("insert into characters (race, gender, name) values (?, ?, ?)",
            new Object[] { new String[] {"Android"}, new String[] {"male", "robot"}, "Marvin3"}
        );
        refresh();

        execute("select gender from characters group by gender");
    }

    @Test
    public void testDropTable() throws Exception {
        execute("create table test (col1 integer primary key, col2 string)");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("test"))
                .actionGet().isExists());

        execute("drop table test");
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
        assertFalse(client().admin().indices().exists(new IndicesExistsRequest("test"))
                .actionGet().isExists());
    }

    @Test (expected = TableUnknownException.class)
    public void testDropUnknownTable() throws Exception {
        execute("drop table test");
    }

    @Test
    public void selectMultiGetRequestWithColumnAlias() throws IOException {
        createTestIndexWithSomeIdPkMapping();
        execute("insert into test (some_id, foo) values ('1', 'foo')");
        execute("insert into test (some_id, foo) values ('2', 'bar')");
        execute("insert into test (some_id, foo) values ('3', 'baz')");
        refresh();
        execute("SELECT some_id as id, foo from test where some_id IN (?,?)", new Object[]{'1', '2'});
        assertThat(response.rowCount(), is(2L));
        assertThat(response.cols(), arrayContainingInAnyOrder("id", "foo"));
        assertThat(new String[]{(String) response.rows()[0][0], (String) response.rows()[1][0]}, arrayContainingInAnyOrder("1", "2"));
    }

    @Test (expected = TableUnknownException.class)
    public void selectMultiGetRequestFromNonExistentTable() throws IOException {
        execute("SELECT * FROM \"non_existent\" WHERE \"_id\" in (?,?)", new Object[]{"1", "2"});
    }

    @Test
    public void testDeleteWhereVersion() throws Exception {
        execute("create table test (col1 integer primary key, col2 string)");
        ensureGreen();

        execute("insert into test (col1, col2) values (?, ?)", new Object[]{1, "don't panic"});
        refresh();

        execute("select \"_version\" from test where col1 = 1");
        assertEquals(1L, response.rowCount());
        assertEquals(1L, response.rows()[0][0]);
        Long version = (Long)response.rows()[0][0];

        execute("delete from test where col1 = 1 and \"_version\" = ?",
                new Object[]{version});
        assertEquals(1L, response.rowCount());

        // Validate that the row is really deleted
        refresh();
        execute("select * from test where col1 = 1");
        assertEquals(0, response.rowCount());

    }

    @Test
    public void testDeleteWhereVersionWithConflict() throws Exception {
        execute("create table test (col1 integer primary key, col2 string)");
        ensureGreen();

        execute("insert into test (col1, col2) values (?, ?)", new Object[]{1, "don't panic"});
        refresh();

        execute("select \"_version\" from test where col1 = 1");
        assertEquals(1L, response.rowCount());
        assertEquals(1L, response.rows()[0][0]);

        execute("update test set col2 = ? where col1 = ?", new Object[]{"ok now panic", 1});
        assertEquals(1L, response.rowCount());
        refresh();

        execute("delete from test where col1 = 1 and \"_version\" = 1");
        assertEquals(0, response.rowCount());
    }

    @Test
    public void testUpdateWhereVersion() throws Exception {
        execute("create table test (col1 integer primary key, col2 string)");
        ensureGreen();

        execute("insert into test (col1, col2) values (?, ?)", new Object[]{1, "don't panic"});
        refresh();

        execute("select \"_version\" from test where col1 = 1");
        assertEquals(1L, response.rowCount());
        assertEquals(1L, response.rows()[0][0]);

        execute("update test set col2 = ? where col1 = ? and \"_version\" = ?",
                new Object[]{"ok now panic", 1, 1});
        assertEquals(1L, response.rowCount());

        // Validate that the row is really updated
        refresh();
        execute("select col2 from test where col1 = 1");
        assertEquals(1L, response.rowCount());
        assertEquals("ok now panic", response.rows()[0][0]);
    }

    @Test
    public void testUpdateWhereVersionWithConflict() throws Exception {
        execute("create table test (col1 integer primary key, col2 string)");
        ensureGreen();

        execute("insert into test (col1, col2) values (?, ?)", new Object[]{1, "don't panic"});
        refresh();

        execute("select \"_version\" from test where col1 = 1");
        assertEquals(1L, response.rowCount());
        assertEquals(1L, response.rows()[0][0]);

        execute("update test set col2 = ? where col1 = ? and \"_version\" = ?",
                new Object[]{"ok now panic", 1, 1});
        assertEquals(1L, response.rowCount());
        refresh();

        execute("update test set col2 = ? where col1 = ? and \"_version\" = ?",
                new Object[]{"already in panic", 1, 1});
        assertEquals(0, response.rowCount());

        // Validate that the row is really NOT updated
        refresh();
        execute("select col2 from test where col1 = 1");
        assertEquals(1L, response.rowCount());
        assertEquals("ok now panic", response.rows()[0][0]);
    }

    @Test
    public void testSelectMatch() throws Exception {
        execute("create table quotes (quote string)");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        execute("insert into quotes values (?)", new Object[]{"don't panic"});
        refresh();

        execute("select quote from quotes where match(quote, ?)", new Object[]{"don't panic"});
        assertEquals(1L, response.rowCount());
        assertEquals("don't panic", response.rows()[0][0]);
    }

    @Test
    public void testSelectNotMatch() throws Exception {
        execute("create table quotes (quote string)");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        execute("insert into quotes values (?), (?)", new Object[]{"don't panic", "hello"});
        refresh();

        execute("select quote from quotes where not match(quote, ?)",
                new Object[]{"don't panic"});
        assertEquals(1L, response.rowCount());
        assertEquals("hello", response.rows()[0][0]);
    }

    @Test
    public void testSelectOrderByScore() throws Exception {
        execute("create table quotes (quote string index off," +
                "index quote_ft using fulltext(quote))");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        execute("insert into quotes values (?), (?)",
                new Object[]{"Would it save you a lot of time if I just gave up and went mad now?",
                        "Time is an illusion. Lunchtime doubly so"}
        );
        refresh();

        execute("select quote, \"_score\" from quotes where match(quote_ft, ?) " +
                "order by \"_score\" desc",
                new Object[]{"time", "time"});
        assertEquals(2L, response.rowCount());
        assertEquals("Time is an illusion. Lunchtime doubly so", response.rows()[0][0]);
    }

    @Test
    public void testCreateTableWithInlineDefaultIndex() throws Exception {
        execute("create table quotes (quote string index using plain)");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        String quote = "Would it save you a lot of time if I just gave up and went mad now?";
        execute("insert into quotes values (?)", new Object[]{quote});
        refresh();

        // matching does not work on plain indexes
        execute("select quote from quotes where match(quote, 'time')");
        assertEquals(0, response.rowCount());

        // filtering on the actual value does work
        execute("select quote from quotes where quote = ?", new Object[]{quote});
        assertEquals(1L, response.rowCount());
        assertEquals(quote, response.rows()[0][0]);
    }

    @Test
    public void testCreateTableWithInlineIndex() throws Exception {
        execute("create table quotes (quote string index using fulltext)");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        String quote = "Would it save you a lot of time if I just gave up and went mad now?";
        execute("insert into quotes values (?)", new Object[]{quote});
        refresh();

        execute("select quote from quotes where match(quote, 'time')");
        assertEquals(1L, response.rowCount());
        assertEquals(quote, response.rows()[0][0]);

        // filtering on the actual value does not work anymore because its now indexed using the
        // standard analyzer
        execute("select quote from quotes where quote = ?", new Object[]{quote});
        assertEquals(0, response.rowCount());
    }

    @Test
    public void testCreateTableWithIndexOff() throws Exception {
        execute("create table quotes (id int, quote string index off)");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        String quote = "Would it save you a lot of time if I just gave up and went mad now?";
        execute("insert into quotes (id, quote) values (?, ?)", new Object[]{1, quote});
        refresh();

        execute("select quote from quotes where quote = ?", new Object[]{quote});
        assertEquals(0, response.rowCount());

        execute("select quote from quotes where id = 1");
        assertEquals(1L, response.rowCount());
        assertEquals(quote, response.rows()[0][0]);
    }

    @Test
    public void testCreateTableWithIndex() throws Exception {
        execute("create table quotes (quote string, " +
                "index quote_fulltext using fulltext(quote) with (analyzer='english'))");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        String quote = "Would it save you a lot of time if I just gave up and went mad now?";
        execute("insert into quotes values (?)", new Object[]{quote});
        refresh();

        execute("select quote from quotes where match(quote_fulltext, 'time')");
        assertEquals(1L, response.rowCount());
        assertEquals(quote, response.rows()[0][0]);

        // filtering on the actual value does still work
        execute("select quote from quotes where quote = ?", new Object[]{quote});
        assertEquals(1L, response.rowCount());
    }

    @Test
    public void testCreateTableWithCompositeIndex() throws Exception {
        execute("create table novels (title string, description string, " +
                "index title_desc_fulltext using fulltext(title, description) " +
                "with(analyzer='english'))");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("novels"))
                .actionGet().isExists());

        String title = "So Long, and Thanks for All the Fish";
        String description = "Many were increasingly of the opinion that they'd all made a big " +
                "mistake in coming down from the trees in the first place. And some said that " +
                "even the trees had been a bad move, and that no one should ever have left " +
                "the oceans.";
        execute("insert into novels (title, description) values(?, ?)",
                new Object[]{title, description});
        refresh();

        // match token existing at field `title`
        execute("select title, description from novels where match(title_desc_fulltext, 'fish')");
        assertEquals(1L, response.rowCount());
        assertEquals(title, response.rows()[0][0]);
        assertEquals(description, response.rows()[0][1]);

        // match token existing at field `description`
        execute("select title, description from novels where match(title_desc_fulltext, 'oceans')");
        assertEquals(1L, response.rowCount());
        assertEquals(title, response.rows()[0][0]);
        assertEquals(description, response.rows()[0][1]);

        // filtering on the actual values does still work
        execute("select title from novels where title = ?", new Object[]{title});
        assertEquals(1L, response.rowCount());
    }

    @Test
    public void testSelectScoreMatchAll() throws Exception {
        execute("create table quotes (quote string)");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        execute("insert into quotes values (?), (?)",
                new Object[]{"Would it save you a lot of time if I just gave up and went mad now?",
                        "Time is an illusion. Lunchtime doubly so"}
        );
        refresh();

        execute("select quote, \"_score\" from quotes");
        assertEquals(2L, response.rowCount());
        assertEquals(1.0f, response.rows()[0][1]);
        assertEquals(1.0f, response.rows()[1][1]);
    }

    @Test
    public void testSelectWhereScore() throws Exception {
        execute("create table quotes (quote string, " +
                "index quote_ft using fulltext(quote))");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        execute("insert into quotes values (?), (?)",
                new Object[]{"Would it save you a lot of time if I just gave up and went mad now?",
                        "Time is an illusion. Lunchtime doubly so"}
        );
        refresh();

        execute("select quote, \"_score\" from quotes where match(quote_ft, 'time') " +
                "and \"_score\" > 0.98");
        assertEquals(1L, response.rowCount());
        assertEquals(1, ((Float)response.rows()[0][1]).compareTo(0.98f));
    }

    @Test
    public void testSelectMatchAnd() throws Exception {
        execute("create table quotes (id int, quote string, " +
                "index quote_fulltext using fulltext(quote) with (analyzer='english'))");
        assertTrue(client().admin().indices().exists(new IndicesExistsRequest("quotes"))
                .actionGet().isExists());

        execute("insert into quotes (id, quote) values (?, ?), (?, ?)",
                new Object[]{
                        1, "Would it save you a lot of time if I just gave up and went mad now?",
                        2, "Time is an illusion. Lunchtime doubly so"}
        );
        refresh();

        execute("select quote from quotes where match(quote_fulltext, 'time') and id = 1");
        assertEquals(1L, response.rowCount());
    }

    @Test
    public void testSelectFromInformationSchema() throws Exception {
        execute("create table quotes (" +
                "id integer primary key, " +
                "quote string index off, " +
                "index quote_fulltext using fulltext(quote) with (analyzer='snowball')" +
                ") clustered by (id) into 3 shards replicas 10");
        refresh();

        execute("select table_name, number_of_shards, number_of_replicas, routing_column from " +
                "information_schema" +
                ".tables");
        assertEquals(1L, response.rowCount());
        assertEquals("quotes", response.rows()[0][0]);
        assertEquals(3, response.rows()[0][1]);
        assertEquals(10, response.rows()[0][2]);
        assertEquals("id", response.rows()[0][3]);
        assertThat(response.duration(), greaterThanOrEqualTo(0L));

        execute("select * from information_schema.columns");
        assertEquals(2L, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));

        execute("select * from information_schema.table_constraints");
        assertEquals(1L, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));

        execute("select * from information_schema.indices");
        assertEquals(2L, response.rowCount());
        assertEquals("id", response.rows()[0][1]);
        assertEquals("quote_fulltext", response.rows()[1][1]);
        assertThat(response.duration(), greaterThanOrEqualTo(0L));

        execute("select * from information_schema.routines");
        assertEquals(102L, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
    }

    private void nonExistingColumnSetup() {
        execute("create table quotes (" +
                "id integer primary key, " +
                "quote string index off, " +
                "index quote_fulltext using fulltext(quote) with (analyzer='snowball')" +
                ") clustered by (id) into 3 shards");
        refresh();
        execute("insert into quotes (id, quote) values (1, '\"Nothing particularly exciting," +
                "\" it admitted, \"but they are alternatives.\"')");
        execute("insert into quotes (id, quote) values (2, '\"Have another drink," +
                "\" said Trillian. \"Enjoy yourself.\"')");
        refresh();
    }

    @Test
    public void selectNonExistingColumn() throws Exception {
        nonExistingColumnSetup();
        execute("select notExisting from quotes");
        assertEquals(2L, response.rowCount());
        assertEquals("notexisting", response.cols()[0]);
        assertNull(response.rows()[0][0]);
        assertNull(response.rows()[1][0]);
    }

    @Test
    public void selectNonExistingAndExistingColumns() throws Exception {
        nonExistingColumnSetup();
        execute("select \"unknown\", id from quotes order by id asc");
        assertEquals(2L, response.rowCount());
        assertEquals("unknown", response.cols()[0]);
        assertEquals("id", response.cols()[1]);
        assertNull(response.rows()[0][0]);
        assertEquals(1, response.rows()[0][1]);
        assertNull(response.rows()[1][0]);
        assertEquals(2, response.rows()[1][1]);
    }

    @Test
    public void selectWhereNonExistingColumn() throws Exception {
        nonExistingColumnSetup();
        execute("select * from quotes where something > 0");
        assertEquals(0L, response.rowCount());
    }

    @Test
    public void selectWhereNonExistingColumnIsNull() throws Exception {
        nonExistingColumnSetup();
        execute("select * from quotes where something IS NULL");
        assertEquals(2L, response.rowCount());  // something does not exist,
                                                // so we get all documents
    }

    @Test
    public void selectWhereNonExistingColumnWhereIn() throws Exception {
        nonExistingColumnSetup();
        execute("select * from quotes where something IN(1,2,3)");
        assertEquals(0L, response.rowCount());
    }

    @Test
    public void selectWhereNonExistingColumnLike() throws Exception {
        nonExistingColumnSetup();
        execute("select * from quotes where something Like '%bla'");
        assertEquals(0L, response.rowCount());
    }

    @Test
    public void selectWhereNonExistingColumnMatchFunction() throws Exception {
        nonExistingColumnSetup();
        execute("select * from quotes where match(something, 'bla')");
        assertEquals(0L, response.rowCount());
    }

    @Test
    public void selectOrderByNonExistingColumn() throws Exception {
        nonExistingColumnSetup();
        execute("SELECT * from quotes");
        SQLResponse responseWithoutOrder = response;
        execute("SELECT * from quotes order by something");
        assertEquals(responseWithoutOrder.rowCount(), response.rowCount());
        for (int i=0;i<response.rowCount();i++) {
            assertArrayEquals(responseWithoutOrder.rows()[i], response.rows()[i]);
        }
    }

    @Test
    public void testCopyFromFile() throws Exception {
        execute("create table quotes (id int primary key, " +
                "quote string index using fulltext)");
        refresh();

        String filePath = Joiner.on(File.separator).join(copyFilePath, "test_copy_from.json");
        execute("copy quotes from ?", new Object[]{filePath});
        // 2 nodes on same machine resulting in double affected rows
        assertEquals(6L, response.rowCount());
        assertThat(response.duration(), greaterThanOrEqualTo(0L));
        refresh();

        execute("select * from quotes");
        assertEquals(3L, response.rowCount());
    }

    @Test
    public void testCopyFromDirectory() throws Exception {
        execute("create table quotes (id int primary key, " +
                "quote string index using fulltext)");

        execute("copy quotes from ?", new Object[]{copyFilePath});
        // 2 nodes on same machine resulting in double affected rows
        assertEquals(6L, response.rowCount());
        refresh();

        execute("select * from quotes");
        assertEquals(3L, response.rowCount());
    }

    @Test
    public void testCopyFromFilePattern() throws Exception {
        execute("create table quotes (id int primary key, " +
                "quote string index using fulltext)");

        String filePath = Joiner.on(File.separator).join(copyFilePath, "(\\D).json");
        execute("copy quotes from ?", new Object[]{filePath});
        // 2 nodes on same machine resulting in double affected rows
        assertEquals(6L, response.rowCount());
        refresh();

        execute("select * from quotes");
        assertEquals(3L, response.rowCount());
    }

    @Test
    public void testSelectTableAlias() throws Exception {
        execute("create table quotes_en (id int primary key, quote string)");
        execute("create table quotes_de (id int primary key, quote string)");
        client().admin().indices().prepareAliases().addAlias("quotes_en", "quotes")
                .addAlias("quotes_de", "quotes").execute().actionGet();
        ensureGreen();

        execute("insert into quotes_en values (?,?)", new Object[]{1, "Don't panic"});
        assertEquals(1, response.rowCount());
        execute("insert into quotes_de values (?,?)", new Object[]{2, "Keine Panik"});
        assertEquals(1, response.rowCount());
        refresh();

        execute("select quote from quotes where id = ?", new Object[]{1});
        assertEquals(1, response.rowCount());
        execute("select quote from quotes where id = ?", new Object[]{2});
        assertEquals(1, response.rowCount());
    }

    @Test (expected = TableAliasSchemaException.class)
    public void testSelectTableAliasSchemaExceptionColumnDefinition() throws Exception {
        execute("create table quotes_en (id int primary key, quote string, author string)");
        execute("create table quotes_de (id int primary key, quote string)");
        client().admin().indices().prepareAliases().addAlias("quotes_en", "quotes")
                .addAlias("quotes_de", "quotes").execute().actionGet();
        ensureGreen();
        execute("select quote from quotes where id = ?", new Object[]{1});
    }

    @Test (expected = TableAliasSchemaException.class)
    public void testSelectTableAliasSchemaExceptionColumnDataType() throws Exception {
        execute("create table quotes_en (id int primary key, quote int)");
        execute("create table quotes_de (id int primary key, quote string)");
        client().admin().indices().prepareAliases().addAlias("quotes_en", "quotes")
                .addAlias("quotes_de", "quotes").execute().actionGet();
        ensureGreen();
        execute("select quote from quotes where id = ?", new Object[]{1});
    }

    @Test (expected = TableAliasSchemaException.class)
    public void testSelectTableAliasSchemaExceptionPrimaryKeyRoutingColumn() throws Exception {
        execute("create table quotes_en (id int primary key, quote string)");
        execute("create table quotes_de (id int, quote string)");
        client().admin().indices().prepareAliases().addAlias("quotes_en", "quotes")
                .addAlias("quotes_de", "quotes").execute().actionGet();
        ensureGreen();
        execute("select quote from quotes where id = ?", new Object[]{1});
    }

    @Test (expected = TableAliasSchemaException.class)
    public void testSelectTableAliasSchemaExceptionIndices() throws Exception {
        execute("create table quotes_en (id int primary key, quote string)");
        execute("create table quotes_de (id int primary key, quote string index using fulltext)");
        client().admin().indices().prepareAliases().addAlias("quotes_en", "quotes")
                .addAlias("quotes_de", "quotes").execute().actionGet();
        ensureGreen();
        execute("select quote from quotes where id = ?", new Object[]{1});
    }

    @Test
    public void testCountWithGroupByTableAlias() throws Exception {
        execute("create table characters_guide (race string, gender string, name string)");
        execute("insert into characters_guide (race, gender, name) values ('Human', 'male', 'Arthur Dent')");
        execute("insert into characters_guide (race, gender, name) values ('Android', 'male', 'Marving')");
        execute("insert into characters_guide (race, gender, name) values ('Vogon', 'male', 'Jeltz')");
        execute("insert into characters_guide (race, gender, name) values ('Vogon', 'male', 'Kwaltz')");
        refresh();

        execute("create table characters_life (race string, gender string, name string)");
        execute("insert into characters_life (race, gender, name) values ('Rabbit', 'male', 'Agrajag')");
        execute("insert into characters_life (race, gender, name) values ('Human', 'male', 'Ford Perfect')");
        execute("insert into characters_life (race, gender, name) values ('Human', 'female', 'Trillian')");
        refresh();

        client().admin().indices().prepareAliases().addAlias("characters_guide", "characters")
                .addAlias("characters_life", "characters").execute().actionGet();
        ensureGreen();

        execute("select count(*) from characters");
        assertEquals(7L, response.rows()[0][0]);

        execute("select count(*), race from characters group by race order by count(*) desc " +
                "limit 2");
        assertEquals(2, response.rowCount());
        assertEquals("Human", response.rows()[0][1]);
        assertEquals("Vogon", response.rows()[1][1]);
    }

    private String tableAliasSetup() throws Exception {
        String tableName = "mytable";
        String tableAlias = "mytablealias";
        execute(String.format("create table %s (id integer primary key, " +
                "content string)",
                tableName));
        refresh();
        client().admin().indices().prepareAliases().addAlias(tableName,
                tableAlias).execute().actionGet();
        refresh();
        Thread.sleep(20);
        return tableAlias;
    }

    @Test
    public void testCreateTableWithExistingTableAlias() throws Exception {
        String tableAlias = tableAliasSetup();

        expectedException.expect(TableAlreadyExistsException.class);
        expectedException.expectMessage("A table with the same name already exists");

        execute(String.format("create table %s (content string index off)", tableAlias));
    }

    @Test
    public void testDropTableWithTableAlias() throws Exception {
        String tableAlias = tableAliasSetup();
        expectedException.expect(SQLParseException.class);
        expectedException.expectMessage("Table alias not allowed in DROP TABLE statement.");
        execute(String.format("drop table %s", tableAlias));
    }

    @Test
    public void testCopyFromWithTableAlias() throws Exception {
        String tableAlias = tableAliasSetup();
        expectedException.expect(SQLParseException.class);
        expectedException.expectMessage("Table alias not allowed in COPY statement.");

        execute(String.format("copy %s from '/tmp/file.json'", tableAlias));

    }

    @Test
    public void testInsertWithTableAlias() throws Exception {
        String tableAlias = tableAliasSetup();
        expectedException.expect(SQLParseException.class);
        expectedException.expectMessage("Table alias not allowed in INSERT statement.");

        execute(
                String.format("insert into %s (id, content) values (?, ?)", tableAlias),
                new Object[]{1, "bla"}
                );
    }

    @Test
    public void testUpdateWithTableAlias() throws Exception {
        String tableAlias = tableAliasSetup();
        expectedException.expect(SQLParseException.class);
        expectedException.expectMessage("Table alias not allowed in UPDATE statement.");

        execute(
                String.format("update %s set id=?, content=?", tableAlias),
                new Object[]{1, "bla"}
        );
    }

    @Test
    public void testDeleteWithTableAlias() throws Exception {
        String tableAlias = tableAliasSetup();
        expectedException.expect(SQLParseException.class);
        expectedException.expectMessage("Table alias not allowed in DELETE statement.");

        execute(
                String.format("delete from %s where id=?", tableAlias),
                new Object[]{1}
        );
    }
}
