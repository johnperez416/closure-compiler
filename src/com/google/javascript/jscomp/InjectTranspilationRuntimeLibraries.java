/*
 * Copyright 2014 The Closure Compiler Authors.
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

import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.Node;

/**
 * Injects JS library code that may be needed by the transpiled form of the input source code.
 *
 * <p>The intention here is to add anything that could be needed and rely on {@link
 * RemoveUnusedCode} to remove the parts that don't end up getting used. This pass should run before
 * type checking so the type checking code can add type information to the injected JavaScript for
 * checking and optimization purposes.
 *
 * <p>This class also reports an error if it finds getters or setters are used and the language
 * output level is too low to support them. TODO(bradfordcsmith): The getter/setter check should
 * probably be done separately in an earlier pass that only runs when the output language level is
 * ES3 and the input language level is ES5 or greater.
 *
 * <p>TODO(b/120486392): consider merging this pass with {@link InjectRuntimeLibraries} and {@link
 * RewritePolyfills}.
 */
public final class InjectTranspilationRuntimeLibraries extends AbstractPostOrderCallback
    implements CompilerPass {
  private final AbstractCompiler compiler;
  private final boolean getterSetterSupported;
  private boolean injectedClassExtendsLibraries;

  public InjectTranspilationRuntimeLibraries(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.getterSetterSupported =
        !FeatureSet.ES3.contains(compiler.getOptions().getOutputFeatureSet());
    this.injectedClassExtendsLibraries = false;
  }

  @Override
  public void process(Node externs, Node root) {
    FeatureSet used = FeatureSet.ES3;
    for (Node script = root.getFirstChild(); script != null; script = script.getNext()) {
      used = used.with(getScriptFeatures(script));
    }

    FeatureSet outputFeatures = compiler.getOptions().getOutputFeatureSet();

    // Check for references to global `Symbol`, getters/setters, and class `extends` clauses
    if (!outputFeatures.contains(used)) {
      NodeTraversal.traverse(compiler, root, this);
    }

    FeatureSet mustBeCompiledAway = used.without(outputFeatures);

    // We will need these runtime methods when we transpile, but we want the runtime
    // functions to be have JSType applied to it by the type inferrence.

    if (mustBeCompiledAway.contains(Feature.TEMPLATE_LITERALS)) {
      TranspilationUtil.preloadTranspilationRuntimeFunction(compiler, "createtemplatetagfirstarg");
    }

    if (mustBeCompiledAway.contains(Feature.FOR_OF)
        || mustBeCompiledAway.contains(Feature.ARRAY_DESTRUCTURING)
        || mustBeCompiledAway.contains(Feature.OBJECT_PATTERN_REST)) {
      // `makeIterator` isn't needed directly for `OBJECT_PATTERN_REST`, but when we transpile
      // a destructuring case that contains it, we transpile the entire destructured assignment,
      // which may also include `ARRAY_DESTRUCTURING`.
      TranspilationUtil.preloadTranspilationRuntimeFunction(compiler, "makeIterator");
    }

    if (mustBeCompiledAway.contains(Feature.ARRAY_PATTERN_REST)) {
      TranspilationUtil.preloadTranspilationRuntimeFunction(compiler, "arrayFromIterator");
    }

    if (mustBeCompiledAway.contains(Feature.SPREAD_EXPRESSIONS)) {
      // We must automatically generate the default constructor for descendent classes,
      // and those must call super(...arguments), so we end up injecting our own spread
      // expressions for such cases.
      TranspilationUtil.preloadTranspilationRuntimeFunction(compiler, "arrayFromIterable");
    }

    if ((mustBeCompiledAway.contains(Feature.OBJECT_LITERALS_WITH_SPREAD)
            || mustBeCompiledAway.contains(Feature.OBJECT_PATTERN_REST))
        && !outputFeatures.contains(FeatureSet.ES2015)) {
      // We need `Object.assign` to transpile `obj = {a, ...rest};` or `const {a, ...rest} = obj;`,
      // but the output language level doesn't indicate that it is guaranteed to be present, so
      // we'll include our polyfill.
      compiler.ensureLibraryInjected("es6/object/assign", /* force= */ false);
    }

    if (mustBeCompiledAway.contains(Feature.CLASS_GETTER_SETTER)) {
      compiler.ensureLibraryInjected("util/global", /* force= */ false);
    }

    if (mustBeCompiledAway.contains(Feature.GENERATORS)) {
      compiler.ensureLibraryInjected("es6/generator_engine", /* force= */ false);
    }

    if (mustBeCompiledAway.contains(Feature.ASYNC_FUNCTIONS)) {
      compiler.ensureLibraryInjected("es6/execute_async_generator", /* force= */ false);
    }

    if (mustBeCompiledAway.contains(Feature.ASYNC_GENERATORS)) {
      compiler.ensureLibraryInjected("es6/async_generator_wrapper", /* force= */ false);
    }

    if (mustBeCompiledAway.contains(Feature.FOR_AWAIT_OF)) {
      compiler.ensureLibraryInjected("es6/util/makeasynciterator", /* force= */ false);
    }

    if (mustBeCompiledAway.contains(Feature.REST_PARAMETERS)) {
      compiler.ensureLibraryInjected("es6/util/restarguments", /* force= */ false);
    }

    if (compiler.getOptions().getInstrumentAsyncContext()
        // NOTE: async functions only matter for output features, since we don't bother
        // instrumenting them if they're being transpiled away.  Generators are relevant
        // regardless of whether they're transpiled or not.
        && (outputFeatures.contains(Feature.ASYNC_FUNCTIONS)
            || used.contains(Feature.GENERATORS)
            || used.contains(Feature.ASYNC_GENERATORS))) {
      compiler.ensureLibraryInjected("es6/asynccontext/runtime", /* force= */ false);
    }
  }

  private static FeatureSet getScriptFeatures(Node script) {
    FeatureSet features = NodeUtil.getFeatureSetOfScript(script);
    return features != null ? features : FeatureSet.ES3;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    switch (n.getToken()) {
      case GETPROP:
        if (!n.isFromExterns()) {
          visitGetprop(t, n);
        }
        break;

        // TODO(johnlenz): this check doesn't belong here.
      case GETTER_DEF:
      case SETTER_DEF:
        if (!getterSetterSupported) {
          TranspilationUtil.cannotConvert(
              compiler, n, "ES5 getters/setters (consider using --language_out=ES5)");
        }
        break;

      case CLASS:
        // This is technically an optimization - we could just always inject these when we see
        // Feature.CLASSES. That's fine for real code, but just makes some unit testing
        // harder because more runtime libraries are injected.
        Node superclass = n.getSecondChild();
        if (!injectedClassExtendsLibraries && !superclass.isEmpty()) {
          TranspilationUtil.preloadTranspilationRuntimeFunction(compiler, "construct");
          TranspilationUtil.preloadTranspilationRuntimeFunction(compiler, "inherits");
          // We must automatically generate the default constructor for descendent classes,
          // and those must call super(...arguments), so we end up injecting our own spread
          // expressions for such cases.
          TranspilationUtil.preloadTranspilationRuntimeFunction(compiler, "arrayFromIterable");
          injectedClassExtendsLibraries = true;
        }
        break;
      default:
        break;
    }
  }

  /** @return Whether {@code n} is a reference to the global "Symbol" function. */
  private boolean isGlobalSymbol(NodeTraversal t, Node n) {
    if (!n.matchesName("Symbol")) {
      return false;
    }
    Var var = t.getScope().getVar("Symbol");
    return var == null || var.isGlobal();
  }

  private void visitGetprop(NodeTraversal t, Node n) {
    Node receiverNode = n.getFirstChild();
    if (isGlobalSymbol(t, receiverNode)) {
      compiler.ensureLibraryInjected("es6/symbol", false);
    }
  }
}
