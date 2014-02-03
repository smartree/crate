/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operator.reference.sys.shard;

import io.crate.metadata.ReferenceInfo;
import io.crate.metadata.shard.sys.SysShardExpression;
import io.crate.metadata.sys.SystemReferences;
import org.cratedb.DataType;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.index.shard.ShardId;

public class ShardIdExpression extends SysShardExpression<Integer> {

    public static final String COLNAME = "id";


    public static final ReferenceInfo INFO_ID = SystemReferences.registerShardReference(
            COLNAME, DataType.INTEGER);


    private final ShardId shardId;

    @Inject
    public ShardIdExpression(ShardId shardId) {
        this.shardId = shardId;
    }

    @Override
    public Integer value() {
        return shardId.getId();
    }

    @Override
    public ReferenceInfo info() {
        return INFO_ID;
    }

}
