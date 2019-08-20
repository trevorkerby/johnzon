/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.johnzon.core;

import java.io.StringReader;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.stream.JsonParser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JsonParserStreamingTest {

    @Test
    public void testArrayStream() {
        {
            // int
            String json = "[1,2,3,4]";
            String sum = parserAndConcat(json);
            assertEquals(json, sum);
        }
        {
            // STring
            String json = "[\"1\",\"2\",\"3\",\"4\"]";
            String sum = parserAndConcat(json);
            assertEquals(json, sum);
        }
    }

    @Test
    public void testValueStreamForArrays() {
        {
            // int
            String json = "[1,2,3,4]";
            String sum = parserAndConcat(json);
            assertEquals(json, sum);
        }
        {
            // String
            String json = "[\"1\",\"2\",\"3\",\"4\"]";
            String sum = parserAndConcat(json);
            assertEquals(json, sum);
        }

        {
            // single String
            String json = "[\"In a galaxy far far away\"]";
            String sum = parserAndConcat(json);
            assertEquals(json, sum);
        }
    }

    @Test
    public void testValueStream() {
        {
            String json = "\"a string value\"";
            String sum = parserAndConcat(json);
            assertEquals(json, sum);
        }

        {
            String sum = parserAndConcat("234");
            assertEquals("234", sum);
        }
    }

    @Test
    public void testValueStreamForObjects() {
        String json = "{\"address\":\"somewhere else\"}";
        String sum = parserAndConcat(json);
        assertEquals(json, sum);
    }

    private String parserAndConcat(String json) {
        StringReader sr = new StringReader(json);
        JsonParser jsonParser = Json.createParser(sr);

        String sum = jsonParser.getValueStream()
                .map(v -> v.toString())
                .collect(Collectors.joining(","));
        return sum;
    }
}
