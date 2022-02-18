/*
 * Copyright 2022 RelationalAI, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.relationalai;

import java.io.ByteArrayOutputStream;
import com.jsoniter.output.JsonStream;

// The base class for RelationalAI system entities, all of which can be
// serialized as JSON objects.
public abstract class Entity {
    public String toString() {
        return toString(0);
    }

    public String toString(int indent) {
        var output = new ByteArrayOutputStream();
        JsonStream.setIndentionStep(indent);
        JsonStream.serialize(this, output);
        return output.toString();
    }
}