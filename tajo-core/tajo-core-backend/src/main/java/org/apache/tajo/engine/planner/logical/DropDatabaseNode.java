/*
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

package org.apache.tajo.engine.planner.logical;

import com.google.common.base.Objects;
import com.google.gson.annotations.Expose;
import org.apache.tajo.engine.planner.PlanString;

public class DropDatabaseNode extends LogicalNode implements Cloneable {
  @Expose private String databaseName;
  @Expose private boolean ifExists;

  public DropDatabaseNode(int pid) {
    super(pid, NodeType.DROP_DATABASE);
  }

  public void init(String databaseName, boolean ifExists) {
    this.databaseName = databaseName;
    this.ifExists = ifExists;
  }

  public String getDatabaseName() {
    return this.databaseName;
  }

  public boolean isIfExists() {
    return ifExists;
  }

  @Override
  public PlanString getPlanString() {
    return new PlanString(this).appendTitle(ifExists ? " IF EXISTS " : " ").appendTitle(databaseName);
  }

  public int hashCode() {
    return Objects.hashCode(databaseName, ifExists);
  }

  public boolean equals(Object obj) {
    if (obj instanceof DropDatabaseNode) {
      DropDatabaseNode other = (DropDatabaseNode) obj;
      return super.equals(other) && this.databaseName.equals(other.databaseName) && ifExists == other.ifExists;
    } else {
      return false;
    }
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    DropDatabaseNode dropTableNode = (DropDatabaseNode) super.clone();
    dropTableNode.databaseName = databaseName;
    return dropTableNode;
  }

  @Override
  public String toString() {
    return "DROP DATABASE " + (ifExists ? "IF EXISTS ":"") + databaseName;
  }

  @Override
  public void preOrder(LogicalNodeVisitor visitor) {
    visitor.visit(this);
  }

  @Override
  public void postOrder(LogicalNodeVisitor visitor) {
    visitor.visit(this);
  }
}
