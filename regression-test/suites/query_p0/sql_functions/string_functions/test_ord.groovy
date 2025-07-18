// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

suite("test_ord") {
    // Test ord function with various inputs
    qt_sql "select ord('A');"
    qt_sql "select ord('a');"
    qt_sql "select ord('0');"
    qt_sql "select ord('9');"
    qt_sql "select ord('!');"
    qt_sql "select ord('@');"
    qt_sql "select ord('中');"
    qt_sql "select ord('hello');"
    qt_sql "select ord('world');"
    qt_sql "select ord('');"
    qt_sql "select ord(null);"
    
    // Test with table data
    sql "DROP TABLE IF EXISTS test_ord_table;"
    sql """
        CREATE TABLE test_ord_table (
            id INT,
            str_col STRING
        )
        DISTRIBUTED BY HASH(id) BUCKETS 1
        PROPERTIES (
            "replication_num" = "1"
        );
    """
    
    sql """
        INSERT INTO test_ord_table VALUES
        (1, 'A'),
        (2, 'a'),
        (3, '0'),
        (4, '9'),
        (5, '!'),
        (6, '@'),
        (7, 'hello'),
        (8, 'world'),
        (9, ''),
        (10, null);
    """
    
    qt_sql "select id, str_col, ord(str_col) from test_ord_table order by id;"
    
    // Test that ord returns same values as ascii
    qt_sql "select ord('A') = ascii('A');"
    qt_sql "select ord('a') = ascii('a');"
    qt_sql "select ord('0') = ascii('0');"
    qt_sql "select ord('!') = ascii('!');"
    qt_sql "select ord('hello') = ascii('hello');"
    qt_sql "select ord('') = ascii('');"
    
    // Test edge cases
    qt_sql "select ord(char(0));"
    qt_sql "select ord(char(127));"
    qt_sql "select ord(char(255));"
    
    sql "DROP TABLE IF EXISTS test_ord_table;"
}