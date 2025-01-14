/*
 * Copyright 2022 RelationalAI, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"): you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.relationalai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

// Test transaction execution.
@TestInstance(Lifecycle.PER_CLASS)
public class ExecuteTest extends UnitTest {
    @Test void testExecute() throws HttpError, InterruptedException, IOException {
        var client = createClient();

        ensureDatabase(client);

        var query = "x, x^2, x^3, x^4 from x in {1; 2; 3; 4; 5}";

        var rsp = client.execute(databaseName, engineName, query, true);
        assertEquals(rsp.aborted, false);
        var output = rsp.output;
        assertEquals(output.length, 1);
        var relation = output[0];
        var relKey = relation.relKey;
        assertEquals(relKey.name, "output");
        assertArrayEquals(relKey.keys, new String[] {"Int64", "Int64", "Int64"});
        assertArrayEquals(relKey.values, new String[] {"Int64"});
        var columns = relation.columns;
        var expected = new Object[][] {
                {1., 2., 3., 4., 5.},
                {1., 4., 9., 16., 25.},
                {1., 8., 27., 64., 125.},
                {1., 16., 81., 256., 625.}};
        assertArrayEquals(expected, columns);
    }

    @AfterAll void tearDown() throws IOException, HttpError, InterruptedException {
        var client = createClient();
        var deleteRsp = client.deleteDatabase(databaseName);
        assertEquals(databaseName, deleteRsp.name);
        try {
            // deleteEngineWait terminates its polling loop with a 404
            client.deleteEngineWait(engineName);
        } catch (HttpError e) {
            assertEquals(e.statusCode, 404);
        }
    }
}
