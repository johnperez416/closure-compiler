/*
 * Copyright 2008 The Closure Compiler Authors.
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
package com.google.javascript.jscomp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.javascript.rhino.InputId;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.StaticRef;
import com.google.javascript.rhino.StaticSourceFile;
import com.google.javascript.rhino.Token;
import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * Represents a single declaration or reference to a variable. Note that references can only be used
 * with untyped scopes and traversals.
 */
public final class Reference implements StaticRef, Serializable {

  // Favor EnumSet over ImmutableSet for performance
  private static final ImmutableSet<Token> DECLARATION_PARENTS =
      Sets.immutableEnumSet(
          Token.VAR,
          Token.LET,
          Token.CONST,
          Token.PARAM_LIST,
          Token.FUNCTION,
          Token.CLASS,
          Token.CATCH);

  private final Node nameNode;
  private final BasicBlock basicBlock;
  private final Scope scope;
  private final InputId inputId;

  Reference(Node nameNode, NodeTraversal t, BasicBlock basicBlock) {
    this(nameNode, basicBlock, t.getScope(), t.getInput().getInputId());
  }

  private Reference(
      Node nameNode, @Nullable BasicBlock basicBlock, @Nullable Scope scope, InputId inputId) {
    this.nameNode = nameNode;
    this.basicBlock = basicBlock;
    this.scope = scope;
    this.inputId = inputId;
  }

  @Override
  public String toString() {
    return nameNode.toString();
  }

  /**
   * Creates a variable reference in a given script file name, used in tests.
   *
   * @return The created reference.
   */
  @VisibleForTesting
  static Reference createRefForTest(CompilerInput input) {
    return new Reference(new Node(Token.NAME), null, null, input.getInputId());
  }

  /** Makes a copy of the current reference using a new Scope instance. */
  Reference cloneWithNewScope(Scope newScope) {
    return new Reference(nameNode, basicBlock, newScope, inputId);
  }

  @Override
  public Var getSymbol() {
    return scope.getVar(nameNode.getString());
  }

  @Override
  public Node getNode() {
    return nameNode;
  }

  public InputId getInputId() {
    return inputId;
  }

  @Override
  public StaticSourceFile getSourceFile() {
    return nameNode.getStaticSourceFile();
  }

  boolean isDeclaration() {
    return isDeclarationHelper(nameNode);
  }

  private static boolean isDeclarationHelper(Node node) {
    Node parent = node.getParent();

    // Special case for class B extends A, A is not a declaration.
    if (parent.isClass() && !node.isFirstChildOf(parent)) {
      return false;
    }

    // This condition can be true during InlineVariables.
    if (parent.getParent() == null) {
      return false;
    }

    if (NodeUtil.isNameDeclaration(parent.getParent()) && node == parent.getSecondChild()) {
      // This is the RHS of a var/let/const and thus not a declaration.
      return false;
    }

    // Special cases for destructuring patterns.
    if (parent.isDestructuringLhs()
        || parent.isDestructuringPattern()
        || parent.isRest()
        || (parent.isStringKey() && parent.getParent().isObjectPattern())
        || (parent.isComputedProp()
            && parent.getParent().isObjectPattern()
            && node == parent.getLastChild())
        || (parent.isDefaultValue() && node == parent.getFirstChild())) {
      return isDeclarationHelper(parent);
    }

    if (parent.isImport()) {
      return true;
    }

    if (parent.isImportSpec() && node == parent.getLastChild()) {
      return true;
    }

    // Special case for arrow function
    if (parent.isArrowFunction()) {
      return node == parent.getFirstChild();
    }

    return DECLARATION_PARENTS.contains(parent.getToken());
  }

  public boolean isVarDeclaration() {
    return getParent().isVar();
  }

  boolean isLetDeclaration() {
    return getParent().isLet();
  }

  boolean isDotPropertyAccess() {
    return getParent().isGetProp();
  }

  boolean isAssignedToObjectDestructuringPattern() {
    final Node parent = getParent();
    // `let { propName: varName } = ref;`
    // or
    // `({ propName: varName } = ref);`
    return (parent.isDestructuringLhs() || parent.isAssign())
        && parent.getFirstChild().isObjectPattern();
  }

  public boolean isConstDeclaration() {
    return getParent().isConst();
  }

  boolean isHoistedFunction() {
    return NodeUtil.isHoistedFunctionDeclaration(getParent());
  }

  /** Determines whether the variable is initialized at the declaration. */
  public boolean isInitializingDeclaration() {
    // VAR and LET are the only types of variable declarations that may not initialize
    // their variables. Catch blocks, named functions, and parameters all do.
    return (isDeclaration() && !getParent().isVar() && !getParent().isLet())
        || nameNode.hasChildren();
  }

  /**
   * @return For an assignment, variable declaration, or function declaration return the assigned
   *     value, otherwise null.
   */
  Node getAssignedValue() {
    return NodeUtil.getRValueOfLValue(nameNode);
  }

  BasicBlock getBasicBlock() {
    return basicBlock;
  }

  Node getParent() {
    return getNode().getParent();
  }

  Node getGrandparent() {
    return getNode().getGrandparent();
  }

  private static boolean isLhsOfEnhancedForExpression(Node n) {
    Node parent = n.getParent();
    return NodeUtil.isEnhancedFor(parent) && parent.getFirstChild() == n;
  }

  public boolean isSimpleAssignmentToName() {
    Node parent = getParent();
    return parent.isAssign() && parent.getFirstChild() == nameNode;
  }

  /**
   * Returns whether the name node for this reference is an lvalue. TODO(tbreisacher): This method
   * disagrees with NodeUtil#isLValue for "var x;" and "let x;". Consider updating it to match.
   */
  public boolean isLvalue() {
    Node parent = getParent();
    Token parentType = parent.getToken();
    return switch (parentType) {
      case VAR, LET, CONST -> (nameNode.hasChildren() || isLhsOfEnhancedForExpression(nameNode));
      case DEFAULT_VALUE -> parent.getFirstChild() == nameNode;
      case INC, DEC, CATCH, ITER_REST, OBJECT_REST, PARAM_LIST -> true;
      case FOR, FOR_IN, FOR_OF, FOR_AWAIT_OF ->
          NodeUtil.isEnhancedFor(parent) && parent.getFirstChild() == nameNode;
      case ARRAY_PATTERN, STRING_KEY, COMPUTED_PROP -> NodeUtil.isLhsByDestructuring(nameNode);
      default -> (NodeUtil.isAssignmentOp(parent) && parent.getFirstChild() == nameNode);
    };
  }

  Scope getScope() {
    return scope;
  }
}
