/**
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

package org.apache.tajo.engine.planner;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import org.apache.tajo.algebra.JoinType;
import org.apache.tajo.catalog.Column;
import org.apache.tajo.catalog.Schema;
import org.apache.tajo.catalog.SortSpec;
import org.apache.tajo.common.TajoDataTypes.DataType;
import org.apache.tajo.engine.eval.*;
import org.apache.tajo.engine.exception.InvalidQueryException;
import org.apache.tajo.engine.planner.LogicalPlan.PIDFactory;
import org.apache.tajo.engine.planner.global.ExecutionPlan;
import org.apache.tajo.engine.planner.logical.*;
import org.apache.tajo.storage.TupleComparator;

import java.util.*;

public class PlannerUtil {
  public static String normalizeTableName(String tableName) {
    return tableName.toLowerCase();
  }

  public static boolean checkIfDDLPlan(LogicalNode node) {
    LogicalNode baseNode = node;
    if (node instanceof LogicalRootNode) {
      baseNode = ((LogicalRootNode) node).getChild();
    }

    return baseNode.getType() == NodeType.CREATE_TABLE || baseNode.getType() == NodeType.DROP_TABLE;
  }

  /**
   * Get all scan nodes from a logical operator tree.
   *
   * @param node a start node
   * @return an array of relation names
   */
  public static String [] getRelationLineage(LogicalNode node) {
    LogicalNode [] scans =  PlannerUtil.findAllNodes(node, NodeType.SCAN);
    String [] tableNames = new String[scans.length];
    ScanNode scan;
    for (int i = 0; i < scans.length; i++) {
      scan = (ScanNode) scans[i];
      tableNames[i] = scan.getCanonicalName();
    }
    return tableNames;
  }

  public static String [] getRelationLineage(ExecutionPlan plan, LogicalNode node) {
    LogicalNode [] scans =  PlannerUtil.findAllNodes(plan, node, NodeType.SCAN);
    String [] tableNames = new String[scans.length];
    ScanNode scan;
    for (int i = 0; i < scans.length; i++) {
      scan = (ScanNode) scans[i];
      tableNames[i] = scan.getCanonicalName();
    }
    return tableNames;
  }

  /**
   * Get all scan nodes from a logical operator tree within a query block
   *
   * @param node a start node
   * @return an array of relation names
   */
  public static Collection<String> getRelationLineageWithinQueryBlock(LogicalPlan plan, LogicalNode node)
      throws PlanningException {
    RelationFinderVisitor visitor = new RelationFinderVisitor();
    visitor.visit(null, plan, node);
    return visitor.getFoundRelations();
  }

  public static class RelationFinderVisitor extends BasicLogicalPlanVisitor<Object, LogicalNode> {
    private Set<String> foundRelNameSet = Sets.newHashSet();

    public Set<String> getFoundRelations() {
      return foundRelNameSet;
    }

    @Override
    public LogicalNode visitChild(Object context, LogicalPlan plan, LogicalNode node, Stack<LogicalNode> stack)
        throws PlanningException {
      if (node.getType() != NodeType.TABLE_SUBQUERY) {
        super.visitChild(context, plan, node, stack);
      }

      if (node instanceof RelationNode) {
        foundRelNameSet.add(((RelationNode) node).getCanonicalName());
      }

      return node;
    }
  }
  
  /**
   * Delete the logical node from a plan.
   *
   * @param parent this node must be a parent node of one node to be removed.
   * @param tobeRemoved this node must be a child node of the parent.
   */
  public static LogicalNode deleteNode(LogicalNode parent, LogicalNode tobeRemoved) {
    Preconditions.checkArgument(tobeRemoved instanceof UnaryNode,
        "ERROR: the logical node to be removed must be unary node.");

    UnaryNode child = (UnaryNode) tobeRemoved;
    LogicalNode grandChild = child.getChild();
    if (parent instanceof UnaryNode) {
      UnaryNode unaryParent = (UnaryNode) parent;

      Preconditions.checkArgument(unaryParent.getChild() == child,
          "ERROR: both logical node must be parent and child nodes");
      unaryParent.setChild(grandChild);

    } else if (parent instanceof BinaryNode) {
      BinaryNode binaryParent = (BinaryNode) parent;
      if (binaryParent.getLeftChild().deepEquals(child)) {
        binaryParent.setLeftChild(grandChild);
      } else if (binaryParent.getRightChild().deepEquals(child)) {
        binaryParent.setRightChild(grandChild);
      } else {
        throw new IllegalStateException("ERROR: both logical node must be parent and child nodes");
      }
    } else {
      throw new InvalidQueryException("Unexpected logical plan: " + parent);
    }    
    return child;
  }

  public static void replaceNode(LogicalPlan plan, LogicalNode startNode, LogicalNode oldNode, LogicalNode newNode) {
    LogicalNodeReplaceVisitor replacer = new LogicalNodeReplaceVisitor(oldNode, newNode);
    try {
      replacer.visit(null, plan, startNode);
    } catch (PlanningException e) {
      e.printStackTrace();
    }
  }

  public static class LogicalNodeReplaceVisitor extends BasicLogicalPlanVisitor<Object, LogicalNode> {
    private LogicalNode target;
    private LogicalNode tobeReplaced;

    public LogicalNodeReplaceVisitor(LogicalNode target, LogicalNode tobeReplaced) {
      this.target = target;
      this.tobeReplaced = tobeReplaced;
    }

    @Override
    public LogicalNode visitChild(Object context, LogicalPlan plan, LogicalNode node, Stack<LogicalNode> stack)
        throws PlanningException {
      super.visitChild(context, plan, node, stack);

      if (node.deepEquals(target)) {
        LogicalNode parent = stack.peek();

        if (parent instanceof BinaryNode) {
          BinaryNode binaryParent = (BinaryNode) parent;
          if (binaryParent.getLeftChild().deepEquals(target)) {
            binaryParent.setLeftChild(tobeReplaced);
          }
          if (binaryParent.getRightChild().deepEquals(target)) {
            binaryParent.setRightChild(tobeReplaced);
          }
        } else if (parent instanceof UnaryNode) {
          UnaryNode unaryParent = (UnaryNode) parent;
          unaryParent.setChild(tobeReplaced);
        }
      }
      return node;
    }
  }
  
  public static void replaceNode(LogicalNode plan, LogicalNode newNode, NodeType type) {
    LogicalNode parent = findTopParentNode(plan, type);
    Preconditions.checkArgument(parent instanceof UnaryNode);
    Preconditions.checkArgument(!(newNode instanceof BinaryNode));
    UnaryNode parentNode = (UnaryNode) parent;
    LogicalNode child = parentNode.getChild();
    if (child instanceof UnaryNode) {
      ((UnaryNode) newNode).setChild(((UnaryNode) child).getChild());
    }
    parentNode.setChild(newNode);
  }

  public static GroupbyNode[] transformGroupbyTo2Pv2(LogicalPlan plan, GroupbyNode groupBy) {
    Preconditions.checkNotNull(groupBy);

    GroupbyNode parent = null, child = null;

    // cloning groupby node
    try {
      parent = groupBy;
      child = (GroupbyNode) groupBy.clone();
      child.setPid(plan.newPID());
    } catch (CloneNotSupportedException e) {
      e.printStackTrace();
    }

    List<Target> firstStepTargets = Lists.newArrayList();
    Target[] secondTargets = parent.getTargets();
    Target[] firstTargets = child.getTargets();

    Target second;
    Target first;
    int targetId =  0;
    for (int i = 0; i < firstTargets.length; i++) {
      second = secondTargets[i];
      first = firstTargets[i];

      List<AggregationFunctionCallEval> secondStepFunctions = EvalTreeUtil.findDistinctAggFunction(second.getEvalTree());
      List<AggregationFunctionCallEval> firstStepFunctions = EvalTreeUtil.findDistinctAggFunction(first.getEvalTree());

      if (firstStepFunctions.size() == 0) {
        firstStepTargets.add(first);
        targetId++;
      } else {
        for (AggregationFunctionCallEval func : firstStepFunctions) {
          Target newTarget;

          if (func.isDistinct()) {
            List<Column> fields = EvalTreeUtil.findAllColumnRefs(func);
            newTarget = new Target(new FieldEval(fields.get(0)));
            String targetName = "column_" + (targetId++);
            newTarget.setAlias(targetName);

            AggregationFunctionCallEval secondFunc = null;
            for (AggregationFunctionCallEval sf : secondStepFunctions) {
              if (func.equals(sf)) {
                secondFunc = sf;
                break;
              }
            }

            secondFunc.setArgs(new EvalNode [] {new FieldEval(
                new Column(targetName, newTarget.getEvalTree().getValueType()))});
          } else {
            func.setFirstPhase();
            newTarget = new Target(func);
            String targetName = "column_" + (targetId++);
            newTarget.setAlias(targetName);

            AggregationFunctionCallEval secondFunc = null;
            for (AggregationFunctionCallEval sf : secondStepFunctions) {
              if (func.equals(sf)) {
                secondFunc = sf;
                break;
              }
            }
            secondFunc.setArgs(new EvalNode [] {new FieldEval(
                new Column(targetName, newTarget.getEvalTree().getValueType()))});
          }
          firstStepTargets.add(newTarget);
        }
      }

      // Getting new target list and updating input/output schema from the new target list.
      Target[] targetArray = firstStepTargets.toArray(new Target[firstStepTargets.size()]);
      Schema targetSchema = PlannerUtil.targetToSchema(targetArray);
      List<Target> newTarget = Lists.newArrayList();
      for (Column column : parent.getGroupingColumns()) {
        if (!targetSchema.contains(column.getQualifiedName())) {
          newTarget.add(new Target(new FieldEval(column)));
        }
      }
      targetArray = ObjectArrays.concat(targetArray, newTarget.toArray(new Target[newTarget.size()]), Target.class);

      child.setTargets(targetArray);
      child.setOutSchema(PlannerUtil.targetToSchema(targetArray));
      // set the groupby chaining
      groupBy.setInSchema(child.getOutSchema());

    }
    return new GroupbyNode[] {parent, child};
  }

  public static GroupbyNode transformGroupbyTo2P(GroupbyNode groupBy) {
    Preconditions.checkNotNull(groupBy);

    GroupbyNode child = null;

    // cloning groupby node
    try {
      child = (GroupbyNode) groupBy.clone();
    } catch (CloneNotSupportedException e) {
      e.printStackTrace();
    }

    List<Target> firstStepTargets = Lists.newArrayList();
    Target[] secondTargets = groupBy.getTargets();
    Target[] firstTargets = child.getTargets();

    Target second;
    Target first;
    int targetId =  0;
    for (int i = 0; i < firstTargets.length; i++) {
      second = secondTargets[i];
      first = firstTargets[i];

      List<AggregationFunctionCallEval> secondStepFunctions = EvalTreeUtil.findDistinctAggFunction(second.getEvalTree());
      List<AggregationFunctionCallEval> firstStepFunctions = EvalTreeUtil.findDistinctAggFunction(first.getEvalTree());

      if (firstStepFunctions.size() == 0) {
        firstStepTargets.add(first);
        targetId++;
      } else {
        for (AggregationFunctionCallEval func : firstStepFunctions) {
          Target newTarget;

          if (func.isDistinct()) {
            List<Column> fields = EvalTreeUtil.findAllColumnRefs(func);
            newTarget = new Target(new FieldEval(fields.get(0)));
            String targetName = "column_" + (targetId++);
            newTarget.setAlias(targetName);

            AggregationFunctionCallEval secondFunc = null;
            for (AggregationFunctionCallEval sf : secondStepFunctions) {
              if (func.equals(sf)) {
                secondFunc = sf;
                break;
              }
            }

            secondFunc.setArgs(new EvalNode [] {new FieldEval(
                new Column(targetName, newTarget.getEvalTree().getValueType()))});
          } else {
            func.setFirstPhase();
            newTarget = new Target(func);
            String targetName = "column_" + (targetId++);
            newTarget.setAlias(targetName);

            AggregationFunctionCallEval secondFunc = null;
            for (AggregationFunctionCallEval sf : secondStepFunctions) {
              if (func.equals(sf)) {
                secondFunc = sf;
                break;
              }
            }
            secondFunc.setArgs(new EvalNode [] {new FieldEval(
                new Column(targetName, newTarget.getEvalTree().getValueType()))});
          }
          firstStepTargets.add(newTarget);
        }
      }

      // Getting new target list and updating input/output schema from the new target list.
      Target[] targetArray = firstStepTargets.toArray(new Target[firstStepTargets.size()]);
      Schema targetSchema = PlannerUtil.targetToSchema(targetArray);
      List<Target> newTarget = Lists.newArrayList();
      for (Column column : groupBy.getGroupingColumns()) {
        if (!targetSchema.contains(column.getQualifiedName())) {
          newTarget.add(new Target(new FieldEval(column)));
        }
      }
      targetArray = ObjectArrays.concat(targetArray, newTarget.toArray(new Target[newTarget.size()]), Target.class);

      child.setTargets(targetArray);
      child.setOutSchema(PlannerUtil.targetToSchema(targetArray));
      // set the groupby chaining
      groupBy.setChild(child);
      groupBy.setInSchema(child.getOutSchema());

    }
    return child;
  }

  public static SortNode[] transformSortTo2p(LogicalPlan plan, SortNode sort) {
    Preconditions.checkArgument(sort != null);
    SortNode parent = null, child = null;
    try {
      parent = sort;
      child = (SortNode) sort.clone();
      child.setPid(plan.newPID());
    } catch (CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }

    parent.setInSchema(child.getOutSchema());

    return new SortNode[]{parent, child};
  }
  
  /**
   * Find the top logical node matched to type from the given node
   * 
   * @param node start node
   * @param type to find
   * @return a found logical node
   */
  public static <T extends LogicalNode> T findTopNode(LogicalNode node, NodeType type) {
    Preconditions.checkNotNull(node);
    Preconditions.checkNotNull(type);
    
    LogicalNodeFinder finder = new LogicalNodeFinder(type);
    node.postOrder(finder);
    
    if (finder.getFoundNodes().size() == 0) {
      return null;
    }
    return (T) finder.getFoundNodes().get(0);
  }

  private static class LogicalNodeFinderForExecPlan {
    private NodeType type;
    private ExecutionPlan plan;
    private LogicalNode node;
    private List<LogicalNode> foundNodes = Lists.newArrayList();

    public LogicalNodeFinderForExecPlan(NodeType type, ExecutionPlan plan, LogicalNode node) {
      this.type = type;
      this.plan = plan;
      this.node = node;
    }

    public LogicalNodeFinderForExecPlan(NodeType type, ExecutionPlan plan) {
      this(type, plan, plan.getTerminalNode());
    }

    public void find() {
      this.visit(node);
    }

    private void visit(LogicalNode node) {
      if (plan.getChildCount(node) > 0) {
        for (LogicalNode child : plan.getChilds(node)) {
          this.visit(child);
        }
      }

      if (node.getType() == type) {
        foundNodes.add(node);
      }
    }

    public List<LogicalNode> getFoundNodes() {
      return foundNodes;
    }
  }

  private static class ParentNodeFinderForExecPlan {
    private NodeType type;
    private ExecutionPlan plan;
    private List<LogicalNode> foundNodes = Lists.newArrayList();

    public ParentNodeFinderForExecPlan(NodeType type, ExecutionPlan plan) {
      this.type = type;
      this.plan = plan;
    }

    public void find() {
      this.visit(plan.getTerminalNode());
    }

    private void visit(LogicalNode node) {
      if (plan.getChildCount(node) > 0) {
        for (LogicalNode child : plan.getChilds(node)) {
          this.visit(child);
        }
        for (LogicalNode child : plan.getChilds(node)) {
          if (child.getType() == type) {
            foundNodes.add(child);
          }
        }
      }
    }

    public List<LogicalNode> getFoundNodes() {
      return foundNodes;
    }
  }

  public static <T extends LogicalNode> T findTopNode(ExecutionPlan executionPlan, NodeType type) {
    Preconditions.checkNotNull(executionPlan);
    Preconditions.checkNotNull(type);

    LogicalNodeFinderForExecPlan finder = new LogicalNodeFinderForExecPlan(type, executionPlan);
    finder.find();

    if (finder.getFoundNodes().size() == 0) {
      return null;
    }
    return (T) finder.getFoundNodes().get(0);
  }

  /**
   * Find the all logical node matched to type from the given node
   *
   * @param node start node
   * @param type to find
   * @return a found logical node
   */
  public static LogicalNode [] findAllNodes(LogicalNode node, NodeType type) {
    Preconditions.checkNotNull(node);
    Preconditions.checkNotNull(type);

    LogicalNodeFinder finder = new LogicalNodeFinder(type);
    node.postOrder(finder);

    if (finder.getFoundNodes().size() == 0) {
      return new LogicalNode[] {};
    }
    List<LogicalNode> founds = finder.getFoundNodes();
    return founds.toArray(new LogicalNode[founds.size()]);
  }

  public static LogicalNode [] findAllNodes(ExecutionPlan plan, LogicalNode node, NodeType type) {
    Preconditions.checkNotNull(node);
    Preconditions.checkNotNull(type);

    LogicalNodeFinderForExecPlan finder = new LogicalNodeFinderForExecPlan(type, plan, node);
    finder.find();

    if (finder.getFoundNodes().size() == 0) {
      return new LogicalNode[] {};
    }
    List<LogicalNode> founds = finder.getFoundNodes();
    return founds.toArray(new LogicalNode[founds.size()]);
  }
  
  /**
   * Find a parent node of a given-typed operator.
   * 
   * @param node start node
   * @param type to find
   * @return the parent node of a found logical node
   */
  public static <T extends LogicalNode> T findTopParentNode(LogicalNode node, NodeType type) {
    Preconditions.checkNotNull(node);
    Preconditions.checkNotNull(type);
    
    ParentNodeFinder finder = new ParentNodeFinder(type);
    node.postOrder(finder);
    
    if (finder.getFoundNodes().size() == 0) {
      return null;
    }
    return (T) finder.getFoundNodes().get(0);
  }

  public static <T extends LogicalNode> T findTopParentNode(ExecutionPlan node, NodeType type) {
    Preconditions.checkNotNull(node);
    Preconditions.checkNotNull(type);

    ParentNodeFinderForExecPlan finder = new ParentNodeFinderForExecPlan(type, node);
    finder.find();

    if (finder.getFoundNodes().size() == 0) {
      return null;
    }
    return (T) finder.getFoundNodes().get(0);
  }

  public static boolean canBeEvaluated(EvalNode eval, LogicalNode node) {
    Set<Column> columnRefs = EvalTreeUtil.findDistinctRefColumns(eval);

    if (node.getType() == NodeType.JOIN) {
      JoinNode joinNode = (JoinNode) node;
      Set<String> tableIds = Sets.newHashSet();
      // getting distinct table references
      for (Column col : columnRefs) {
        if (!tableIds.contains(col.getQualifier())) {
          tableIds.add(col.getQualifier());
        }
      }

      // if the references only indicate two relation, the condition can be
      // pushed into a join operator.
      if (tableIds.size() != 2) {
        return false;
      }

      String [] outer = getRelationLineage(joinNode.getLeftChild());
      String [] inner = getRelationLineage(joinNode.getRightChild());

      Set<String> o = Sets.newHashSet(outer);
      Set<String> i = Sets.newHashSet(inner);
      if (outer == null || inner == null) {
        throw new InvalidQueryException("ERROR: Unexpected logical plan");
      }
      Iterator<String> it = tableIds.iterator();
      if (o.contains(it.next()) && i.contains(it.next())) {
        return true;
      }

      it = tableIds.iterator();

      return i.contains(it.next()) && o.contains(it.next());

    } else if (node instanceof ScanNode) {

      RelationNode scan = (RelationNode) node;

      for (Column col : columnRefs) {
        if (scan.getCanonicalName().equals(col.getQualifier())) {
          Column found = node.getInSchema().getColumnByName(col.getColumnName());
          if (found == null) {
            return false;
          }
        } else {
          return false;
        }
      }

    } else if (node instanceof TableSubQueryNode) {
      TableSubQueryNode subQueryNode = (TableSubQueryNode) node;
      for (Column col : columnRefs) {
        if (subQueryNode.getCanonicalName().equals(col.getQualifier())) {
          Column found = node.getOutSchema().getColumnByName(col.getColumnName());
          if (found == null) {
            return false;
          }
        } else {
          return false;
        }
      }

    } else {

      for (Column col : columnRefs) {
        if (!node.getInSchema().contains(col.getQualifiedName())) {
          return false;
        }
      }
    }

    return true;
  }

  private static class LogicalNodeFinder implements LogicalNodeVisitor {
    private List<LogicalNode> list = new ArrayList<LogicalNode>();
    private final NodeType[] tofind;
    private boolean topmost = false;
    private boolean finished = false;

    public LogicalNodeFinder(NodeType...type) {
      this.tofind = type;
    }

    public LogicalNodeFinder(NodeType[] type, boolean topmost) {
      this(type);
      this.topmost = topmost;
    }

    @Override
    public void visit(LogicalNode node) {
      if (!finished) {
        for (NodeType type : tofind) {
          if (node.getType() == type) {
            list.add(node);
          }
          if (topmost && list.size() > 0) {
            finished = true;
          }
        }
      }
    }

    public List<LogicalNode> getFoundNodes() {
      return list;
    }
  }
  
  private static class ParentNodeFinder implements LogicalNodeVisitor {
    private List<LogicalNode> list = new ArrayList<LogicalNode>();
    private NodeType tofind;

    public ParentNodeFinder(NodeType type) {
      this.tofind = type;
    }

    @Override
    public void visit(LogicalNode node) {
      if (node instanceof UnaryNode) {
        UnaryNode unary = (UnaryNode) node;
        if (unary.getChild().getType() == tofind) {
          list.add(node);
        }
      } else if (node instanceof BinaryNode){
        BinaryNode bin = (BinaryNode) node;
        if (bin.getLeftChild().getType() == tofind ||
            bin.getRightChild().getType() == tofind) {
          list.add(node);
        }
      }
    }

    public List<LogicalNode> getFoundNodes() {
      return list;
    }
  }

  /**
   * fill targets with FieldEvals from a given schema
   *
   * @param schema to be transformed to targets
   * @param targets to be filled
   */
  public static void schemaToTargets(Schema schema, Target [] targets) {
    FieldEval eval;
    for (int i = 0; i < schema.getColumnNum(); i++) {
      eval = new FieldEval(schema.getColumn(i));
      targets[i] = new Target(eval);
    }
  }

  public static Target[] schemaToTargets(Schema schema) {
    Target[] targets = new Target[schema.getColumnNum()];

    FieldEval eval;
    for (int i = 0; i < schema.getColumnNum(); i++) {
      eval = new FieldEval(schema.getColumn(i));
      targets[i] = new Target(eval);
    }
    return targets;
  }

  public static SortSpec[] schemaToSortSpecs(Schema schema) {
    return schemaToSortSpecs(schema.toArray());
  }

  public static SortSpec[] schemaToSortSpecs(Column [] columns) {
    SortSpec[] specs = new SortSpec[columns.length];

    for (int i = 0; i < columns.length; i++) {
      specs[i] = new SortSpec(columns[i], true, false);
    }

    return specs;
  }

  public static SortSpec [] columnsToSortSpec(Collection<Column> columns) {
    SortSpec[] specs = new SortSpec[columns.size()];
    int i = 0;
    for (Column column : columns) {
      specs[i++] = new SortSpec(column, true, false);
    }

    return specs;
  }

  public static Schema sortSpecsToSchema(SortSpec[] sortSpecs) {
    Schema schema = new Schema();
    for (SortSpec spec : sortSpecs) {
      schema.addColumn(spec.getSortKey());
    }

    return schema;
  }

  /**
   * is it join qual or not?
   *
   * @param qual  The condition to be checked
   * @return true if two operands refers to columns and the operator is comparison,
   */
  public static boolean isJoinQual(EvalNode qual) {
    if (AlgebraicUtil.isComparisonOperator(qual)) {
      List<Column> left = EvalTreeUtil.findAllColumnRefs(qual.getLeftExpr());
      List<Column> right = EvalTreeUtil.findAllColumnRefs(qual.getRightExpr());

      if (left.size() == 1 && right.size() == 1 &&
          !left.get(0).getQualifier().equals(right.get(0).getQualifier()))
        return true;
    }

    return false;
  }

  public static SortSpec[][] getSortKeysFromJoinQual(EvalNode joinQual, Schema outer, Schema inner) {
    List<Column []> joinKeyPairs = getJoinKeyPairs(joinQual, outer, inner);
    SortSpec[] outerSortSpec = new SortSpec[joinKeyPairs.size()];
    SortSpec[] innerSortSpec = new SortSpec[joinKeyPairs.size()];

    for (int i = 0; i < joinKeyPairs.size(); i++) {
      outerSortSpec[i] = new SortSpec(joinKeyPairs.get(i)[0]);
      innerSortSpec[i] = new SortSpec(joinKeyPairs.get(i)[1]);
    }

    return new SortSpec[][] {outerSortSpec, innerSortSpec};
  }

  public static TupleComparator[] getComparatorsFromJoinQual(EvalNode joinQual, Schema leftSchema, Schema rightSchema) {
    SortSpec[][] sortSpecs = getSortKeysFromJoinQual(joinQual, leftSchema, rightSchema);
    TupleComparator [] comparators = new TupleComparator[2];
    comparators[0] = new TupleComparator(leftSchema, sortSpecs[0]);
    comparators[1] = new TupleComparator(rightSchema, sortSpecs[1]);
    return comparators;
  }

  /**
   * @return the first array contains left table's columns, and the second array contains right table's columns.
   */
  public static Column [][] joinJoinKeyForEachTable(EvalNode joinQual, Schema leftSchema, Schema rightSchema) {
    List<Column []> joinKeys = getJoinKeyPairs(joinQual, leftSchema, rightSchema);
    Column [] leftColumns = new Column[joinKeys.size()];
    Column [] rightColumns = new Column[joinKeys.size()];
    for (int i = 0; i < joinKeys.size(); i++) {
      leftColumns[i] = joinKeys.get(i)[0];
      rightColumns[i] = joinKeys.get(i)[1];
    }

    return new Column[][] {leftColumns, rightColumns};
  }

  public static List<Column []> getJoinKeyPairs(EvalNode joinQual, Schema leftSchema, Schema rightSchema) {
    JoinKeyPairFinder finder = new JoinKeyPairFinder(leftSchema, rightSchema);
    joinQual.preOrder(finder);
    return finder.getPairs();
  }

  public static class JoinKeyPairFinder implements EvalNodeVisitor {
    private final List<Column []> pairs = Lists.newArrayList();
    private Schema [] schemas = new Schema[2];

    public JoinKeyPairFinder(Schema outer, Schema inner) {
      schemas[0] = outer;
      schemas[1] = inner;
    }

    @Override
    public void visit(EvalNode node) {
      if (EvalTreeUtil.isJoinQual(node)) {
        Column [] pair = new Column[2];

        for (int i = 0; i <= 1; i++) { // access left, right sub expression
          Column column = EvalTreeUtil.findAllColumnRefs(node.getExpr(i)).get(0);
          for (int j = 0; j < schemas.length; j++) {
          // check whether the column is for either outer or inner
          // 0 is outer, and 1 is inner
            if (schemas[j].contains(column.getQualifiedName())) {
              pair[j] = column;
            }
          }
        }

        if (pair[0] == null || pair[1] == null) {
          throw new IllegalStateException("Wrong join key: " + node);
        }
        pairs.add(pair);
      }
    }

    public List<Column []> getPairs() {
      return this.pairs;
    }
  }

  public static Schema targetToSchema(Target[] targets) {
    Schema schema = new Schema();
    for(Target t : targets) {
      DataType type = t.getEvalTree().getValueType();
      String name;
      if (t.hasAlias()) {
        name = t.getAlias();
      } else {
        name = t.getEvalTree().getName();
      }
      schema.addColumn(name, type);
    }

    return schema;
  }

  /**
   * It removes all table names from FieldEvals in targets
   *
   * @param sourceTargets The targets to be stripped
   * @return The stripped targets
   */
  public static Target [] stripTarget(Target [] sourceTargets) {
    Target [] copy = new Target[sourceTargets.length];
    for(int i = 0; i < sourceTargets.length; i++) {
      try {
        copy[i] = (Target) sourceTargets[i].clone();
      } catch (CloneNotSupportedException e) {
        throw new InternalError(e.getMessage());
      }
      if (copy[i].getEvalTree().getType() == EvalType.FIELD) {
        FieldEval fieldEval = (FieldEval) copy[i].getEvalTree();
        if (fieldEval.getColumnRef().hasQualifier()) {
          fieldEval.getColumnRef().setName(fieldEval.getColumnName());
        }
      }
    }

    return copy;
  }

  public static <T extends LogicalNode> T clone(PIDFactory pidFactory, LogicalNode node) {
    try {
      T copy = (T) node.clone();
      copy.setPID(pidFactory.newPID());
      return copy;
    } catch (CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
  }

  public static boolean isCommutativeJoin(JoinType joinType) {
    return joinType == JoinType.INNER;
  }
}
