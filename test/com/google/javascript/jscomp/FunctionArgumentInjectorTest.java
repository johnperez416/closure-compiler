/*
 * Copyright 2009 The Closure Compiler Authors.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.javascript.jscomp.NodeUtil.getFunctionBody;
import static com.google.javascript.rhino.testing.NodeSubject.assertNode;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.rhino.Node;
import java.util.Map.Entry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the static methods in {@link FunctionArgumentInjector}. */
@RunWith(JUnit4.class)
public final class FunctionArgumentInjectorTest {

  private static final ImmutableSet<String> EMPTY_STRING_SET = ImmutableSet.of();

  private Compiler compiler;
  private FunctionArgumentInjector functionArgumentInjector;

  @Before
  public void setUp() {
    compiler = new Compiler();
    CompilerOptions options = new CompilerOptions();

    compiler.initOptions(options);
    functionArgumentInjector = new FunctionArgumentInjector(compiler.getAstAnalyzer());
  }

  @Test
  public void testInject0() {
    Node result =
        functionArgumentInjector.inject(
            compiler,
            getFunctionBody(parseFunction("function f(x) { alert(x); }")),
            null,
            ImmutableMap.of("x", parse("null").getFirstFirstChild()));
    assertNode(result).isEqualTo(getFunctionBody(parseFunction("function f(x) { alert(null); }")));
  }

  @Test
  public void testInject1() {
    Node result =
        functionArgumentInjector.inject(
            compiler,
            getFunctionBody(parseFunction("function f() { alert(this); }")),
            null,
            ImmutableMap.of("this", parse("null").getFirstFirstChild()));
    assertNode(result).isEqualTo(getFunctionBody(parseFunction("function f() { alert(null); }")));
  }

  // TODO(johnlenz): Add more unit tests for "inject"

  @Test
  public void testFindModifiedParameters0() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a){ return a; }")))
        .isEmpty();
  }

  @Test
  public void testFindModifiedParameters1() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a){ return a==0; }")))
        .isEmpty();
  }

  @Test
  public void testFindModifiedParameters2() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(parseFunction("function f(a){ b=a }")))
        .isEmpty();
  }

  @Test
  public void testFindModifiedParameters3() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(parseFunction("function f(a){ a=0 }")))
        .containsExactly("a");
  }

  @Test
  public void testFindModifiedParameters4() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a,b){ a=0;b=0 }")))
        .containsExactly("a", "b");
  }

  @Test
  public void testFindModifiedParameters5() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a,b){ a; if (a) b=0 }")))
        .containsExactly("b");
  }

  @Test
  public void testFindModifiedParameters6() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a,b){ function f(){ a;b; } }")))
        .containsExactly("a", "b");
  }

  @Test
  public void testFindModifiedParameters7() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a,b){ a; function f(){ b; } }")))
        .containsExactly("b");
  }

  @Test
  public void testFindModifiedParameters8() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a,b){ a; function f(){ function g() { b; } } }")))
        .containsExactly("b");
  }

  @Test
  public void testFindModifiedParameters9() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a,b){ (function(){ a;b; }) }")))
        .containsExactly("a", "b");
  }

  @Test
  public void testFindModifiedParameters10() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a,b){ a; (function (){ b; }) }")))
        .containsExactly("b");
  }

  @Test
  public void testFindModifiedParameters11() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a,b){ a; (function(){ (function () { b; }) }) }")))
        .containsExactly("b");
  }

  @Test
  public void testFindModifiedParameters12() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a){ { let a = 1; } }")))
        .isEmpty();
  }

  @Test
  public void testFindModifiedParameters13() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a){ { const a = 1; } }")))
        .isEmpty();
  }

  @Test
  public void testFindModifiedParameters14() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a){ for (a in []) {} }")))
        .containsExactly("a");
  }

  // Note: This is technically incorrect. The parameter a is shadowed, not modified. However, this
  // will just cause the function inliner to do a little bit of unnecessary work; it will not
  // result in incorrect output.
  @Test
  public void testFindModifiedParameters15() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a){ for (const a in []) {} }")))
        .containsExactly("a");
  }

  @Test
  public void testFindModifiedParameters16() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a){ for (a of []) {} }")))
        .containsExactly("a");
  }

  @Test
  public void testFindModifiedParameters17() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a){ [a] = [2]; }")))
        .containsExactly("a");
  }

  @Test
  public void testFindModifiedParameters18() {
    assertThat(
            functionArgumentInjector.findModifiedParameters(
                parseFunction("function f(a){ var [a] = [2]; }")))
        .containsExactly("a");
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps1() {
    // Parameters with side-effects must be executed
    // even if they aren't referenced.
    testNeededTemps("function foo(a,b){}; foo(goo(),goo());", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps1_optChain() {
    // Parameters with side-effects must be executed even if they aren't referenced.
    testNeededTemps("function foo(a,b){}; foo?.(goo(),goo());", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps2() {
    // Unreferenced parameters without side-effects can be ignored.
    testNeededTemps("function foo(a,b){}; foo(1,2);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testAddingTempsForModifiedParams1() {
    // The param is not modified so no need for temps.
    testNeededTemps("function foo(a,b,c){a;b;c;}; foo(x,x,x);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testAddingTempsForModifiedParams2() {
    // The first param is modified, so we add a temp for it.
    testNeededTemps("function foo(a,b,c){a;b;c;}; foo(x=2,x,x);", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testAddingTempsForModifiedParams3() {
    // The second param is modified, with first param using the same var, so we add temps for both.
    testNeededTemps("function foo(a,b,c){a;b;c;}; foo(x,x=2,x);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testAddingTempsForModifiedParams4() {
    // The second param is modified, so we only add temp for both.
    testNeededTemps("function foo(a,b,c){a;b;c;}; foo(y,x=2,x);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testAddingTempsForModifiedParams5() {
    // The second param is modified with a more complex statement, we detect it and add temps for
    // first two params.
    testNeededTemps(
        "function foo(a,b,c){a;b;c;}; foo(x,(y=2,x=1,z=6),x);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testAddingTempsForModifiedParams6() {
    // The second param is modified with a function call, we detect it and add temps for first two
    // params.
    testNeededTemps(
        "function foo(a,b,c){a;b;c;}; foo(x,(function(){y=2,x=1})(),x);",
        "foo",
        ImmutableSet.of("a", "b"));
  }

  @Test
  public void testAddingTempsForModifiedParams7() {
    // The second param is modified with addition, so we add temps for both.
    testNeededTemps(
        "function foo(a,b,c){a;b;c;}; foo(x,x+=2,x);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testAddingTempsForModifiedParams8() {
    // The second param is modified with increment, with first param using the same var, so we add
    // temps for both.
    testNeededTemps("function foo(a,b,c){a;b;c;}; foo(x,x++,x);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testAddingTempsForModifiedParams9() {
    // The second param is used in addition but not modified, no temps needed.
    testNeededTemps("function foo(a,b,c){a;b;c;}; foo(x,x+2,x);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testAddingTempsForModifiedParams10() {
    // The second param is used in addition, the third param is modified, temp all refs.
    testNeededTemps(
        "function foo(a,b,c){a;b;c;}; foo(x,x+2,x=5);", "foo", ImmutableSet.of("a", "b", "c"));
  }

  @Test
  public void testAddingTempsForCallParams1() {
    // The second param is a function call so temp it and previous args with names or calls
    testNeededTemps(
        "function foo(a,b,c){a;b;c;}; var x; function incX(){return ++x}; foo(x,incX(),x);",
        "foo",
        ImmutableSet.of("a", "b"));
  }

  @Test
  public void testAddingTempsForCallParams2() {
    // The second param is a function call so temp it.
    testNeededTemps(
        "function foo(a,b,c){a;b;c;}; var x; function incX(){return ++x}; foo(4,incX(),x);",
        "foo",
        // TODO: b/309593967 The first param is a number, so we can skip creating a temp for it.
        ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps2_optChain() {
    // Unreferenced parameters without side-effects can be ignored.
    testNeededTemps("function foo(a,b){}; foo?.(1,2);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps3() {
    // Referenced parameters without side-effects
    // don't need temps.
    testNeededTemps("function foo(a,b){a;b;}; foo(x,y);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps3_optChain() {
    // Referenced parameters without side-effects don't need temps.
    testNeededTemps("function foo(a,b){a;b;}; foo?.(x,y);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps4() {
    // Parameters referenced after side-effect must be assigned to temps.
    // Since b is getting hoisted as a temp, we must also hoist a.
    testNeededTemps("function foo(a,b){a;goo();b;}; foo(x,y);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testBodyHasConditionalCode() {
    testNeededTemps("function foo(a,b){a;goo?.();b;}; foo(x,y);", "foo", EMPTY_STRING_SET);

    testNeededTemps("function foo(a,b){a; p&&q; b;}; foo(x,y);", "foo", EMPTY_STRING_SET);

    testNeededTemps("function foo(a,b){a; p?q:r; b;}; foo(x,y);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps4_optChain() {
    // Parameters referenced after side-effect must be assigned to temps.
    // Any param before that param must also be assigned to a temp.
    testNeededTemps("function foo(a,b){a;goo();b;}; foo?.(x,y);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps5() {
    // Parameters referenced after out-of-scope side-effect must
    // be assigned to temps.
    testNeededTemps("function foo(a,b){x = b; y = a;}; foo(x,y);", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps5_optChain() {
    // Parameters referenced after out-of-scope side-effect must be assigned to temps.
    testNeededTemps("function foo(a,b){x = b; y = a;}; foo?.(x,y);", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps6() {
    // Parameter referenced after a out-of-scope side-effect must
    // be assigned to a temp.
    testNeededTemps("function foo(a){x++;a;}; foo(x);", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps6_optChain() {
    // Parameter referenced after a out-of-scope side-effect must be assigned to a temp.
    testNeededTemps("function foo(a){x++;a;}; foo?.(x);", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps7() {
    // No temp needed after local side-effects.
    testNeededTemps("function foo(a){var c; c = 0; a;}; foo(x);", "foo", EMPTY_STRING_SET);

    testNeededTemps("function foo(a){let c; c = 0; a;}; foo(x);", "foo", EMPTY_STRING_SET);

    testNeededTemps("function foo(a){const c = 0; a;}; foo(x);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps8() {
    // Temp needed for side-effects to object using local name.
    testNeededTemps(
        "function foo(a){var c = {}; c.goo=0; a;}; foo(x);", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps8_optChain() {
    // Temp needed for side-effects to object using local name.
    testNeededTemps(
        "function foo(a){var c = {}; c.goo=0; a;}; foo?.(x);", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps9() {
    // Parameters referenced in a loop with side-effects must
    // be assigned to temps.
    testNeededTemps(
        "function foo(a,b){while(true){a;goo();b;}}; foo(x,y);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps9_optChain() {
    // Parameters referenced in a loop with side-effects must be assigned to temps.
    testNeededTemps(
        "function foo(a,b){while(true){a;goo();b;}}; foo?.(x,y);",
        "foo",
        ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps10() {
    // No temps for parameters referenced in a loop with no side-effects.
    testNeededTemps(
        "function foo(a,b){while(true){a;true;b;}}; foo(x,y);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps11() {
    // Parameters referenced in a loop with side-effects must
    // be assigned to temps.
    testNeededTemps(
        "function foo(a,b){do{a;b;}while(goo());}; foo(x,y);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps11_optChain() {
    // Parameters referenced in a loop with side-effects must be assigned to temps.
    testNeededTemps(
        "function foo(a,b){do{a;b;}while(goo());}; foo?.(x,y);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps12() {
    // Parameters referenced in a loop with side-effects must
    // be assigned to temps.
    testNeededTemps(
        "function foo(a,b){for(;;){a;b;goo();}}; foo(x,y);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps13() {
    // Parameters referenced in a inner loop without side-effects must
    // be assigned to temps if the outer loop has side-effects.
    testNeededTemps(
        "function foo(a,b){for(;;){for(;;){a;b;}goo();}}; foo(x,y);",
        "foo",
        ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps13_optChain() {
    // Parameters referenced in a inner loop without side-effects must be assigned to temps if the
    // outer loop has side-effects.
    testNeededTemps(
        "function foo(a,b){for(;;){for(;;){a;b;}goo();}}; foo?.(x,y);",
        "foo",
        ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps14() {
    // Parameters referenced in a loop must
    // be assigned to temps.
    testNeededTemps(
        "function foo(a,b){goo();for(;;){a;b;}}; foo(x,y);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps20() {
    // A long string referenced more than once should have a temp.
    testNeededTemps("function foo(a){a;a;}; foo(\"blah blah\");", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps21() {
    // A short string referenced once should not have a temp.
    testNeededTemps("function foo(a){a;a;}; foo(\"\");", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps22() {
    // A object literal not referenced.
    testNeededTemps("function foo(a){}; foo({x:1});", "foo", EMPTY_STRING_SET);
    // A object literal referenced after side-effect, should have a temp.
    testNeededTemps("function foo(a){alert('foo');a;}; foo({x:1});", "foo", ImmutableSet.of("a"));
    // A object literal referenced after side-effect, should have a temp.
    testNeededTemps(
        "function foo(a,b){b;a;}; foo({x:1},alert('foo'));", "foo", ImmutableSet.of("a", "b"));
    // A object literal, referenced more than once, should have a temp.
    testNeededTemps("function foo(a){a;a;}; foo({x:1});", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps22b() {
    // A object literal not referenced.
    testNeededTemps(
        "function foo(a){a(this)}; foo.call(f(),g());", "foo", ImmutableSet.of("a", "this"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps22b_optChain() {
    // A object literal not referenced.
    testNeededTemps(
        "function foo(a){a(this)}; foo?.call(f(),g());", "foo", ImmutableSet.of("a", "this"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps23() {
    // A array literal, not referenced.
    testNeededTemps("function foo(a){}; foo([1,2]);", "foo", EMPTY_STRING_SET);
    // A array literal, referenced once after side-effect, should have a temp.
    testNeededTemps("function foo(a){alert('foo');a;}; foo([1,2]);", "foo", ImmutableSet.of("a"));
    // A array literal, referenced more than once, should have a temp.
    testNeededTemps("function foo(a){a;a;}; foo([1,2]);", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps24() {
    // A regex literal, not referenced.
    testNeededTemps("function foo(a){}; foo(/mac/);", "foo", EMPTY_STRING_SET);
    // A regex literal, referenced once after side-effect, should have a temp.
    testNeededTemps("function foo(a){alert('foo');a;}; foo(/mac/);", "foo", ImmutableSet.of("a"));
    // A regex literal, referenced more than once, should have a temp.
    testNeededTemps("function foo(a){a;a;}; foo(/mac/);", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps25() {
    // A side-effect-less constructor, not referenced.
    testNeededTemps("function foo(a){}; foo(new Date());", "foo", EMPTY_STRING_SET);
    // A side-effect-less constructor, referenced once after sideeffect, should have a temp.
    testNeededTemps(
        "function foo(a){alert('foo');a;}; foo(new Date());", "foo", ImmutableSet.of("a"));
    // A side-effect-less constructor, referenced more than once, should have
    // a temp.
    testNeededTemps("function foo(a){a;a;}; foo(new Date());", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps26() {
    // A constructor, not referenced.
    testNeededTemps("function foo(a){}; foo(new Bar());", "foo", ImmutableSet.of("a"));
    // A constructor, referenced once after a sideeffect, should have a temp.
    testNeededTemps(
        "function foo(a){alert('foo');a;}; foo(new Bar());", "foo", ImmutableSet.of("a"));
    // A constructor, referenced more than once, should have a temp.
    testNeededTemps("function foo(a){a;a;}; foo(new Bar());", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps27() {
    // Ensure the correct parameter is given a temp, when there is
    // a this value in the call. Since `goo()` is a call and needs to be evaluated in a temp before
    // inlining, we also evaluate the previous args in temporaries.
    testNeededTemps(
        "function foo(a,b,c){}; foo.call(this,1,goo(),2);",
        "foo",
        ImmutableSet.of("this", "a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps28() {
    // true/false are don't need temps
    testNeededTemps("function foo(a){a;a;}; foo(true);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps29() {
    // true/false are don't need temps
    testNeededTemps("function foo(a){a;a;}; foo(false);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps30() {
    // true/false are don't need temps
    testNeededTemps("function foo(a){a;a;}; foo(!0);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps31() {
    // true/false are don't need temps
    testNeededTemps("function foo(a){a;a;}; foo(!1);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps32() {
    // void 0 doesn't need a temp
    testNeededTemps("function foo(a){a;a;}; foo(void 0);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps33() {
    // doesn't need a temp
    testNeededTemps("function foo(a){return a;}; foo(new X);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps_argMutatesState() {
    // `y=z` changes state. We create a temp for it and all previous args that it could affect.
    testNeededTemps(
        "function foo(a, b, c){return a+b+c;}; foo(x,y+1,y=z);",
        "foo",
        ImmutableSet.of("a", "b", "c"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTemps_argMutatesState2() {
    // `y=z` happens before `y+1` is read. Safe to inject a temp for evaluating it.
    // Consequently, we also inject a temp for previous arg `a` that it could affect
    testNeededTemps(
        "function foo(a, b, c){return a+b+c;}; foo(x,y=z,y+1);", "foo", ImmutableSet.of("a", "b"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTempsInLoops() {
    // A mutable parameter referenced in loop needs a
    // temporary.
    testNeededTemps("function foo(a){for(;;)a;}; foo(new Bar());", "foo", ImmutableSet.of("a"));

    testNeededTemps("function foo(a){while(true)a;}; foo(new Bar());", "foo", ImmutableSet.of("a"));

    testNeededTemps(
        "function foo(a){do{a;}while(true)}; foo(new Bar());", "foo", ImmutableSet.of("a"));
  }

  @Test
  public void nullishCoalesce() {
    testNeededTemps(
        "function foo(...args) {return args ?? x;} foo(1, 2);", "foo", ImmutableSet.of("args"));
  }

  @Test
  public void testGatherCallArgumentsNeedingTempsRestParam1() {
    testNeededTemps("function foo(...args) {return args;} foo(1, 2);", "foo", EMPTY_STRING_SET);
  }

  @Test
  public void testGatherCallArgumentsNeedingTempsRestParam2() {
    // Since args is hoisted as a temp, we must also hoist the previous argument a
    testNeededTemps(
        "function foo(x, ...args) {return args;} foo(1, 2);", "foo", ImmutableSet.of("x", "args"));
  }

  @Test
  public void testArgMapWithRestParam1() {
    assertArgMapHasKeys(
        "function foo(...args){return args;} foo(1, 2);", "foo", ImmutableSet.of("this", "args"));
  }

  @Test
  public void testArgMapWithRestParam2() {
    assertArgMapHasKeys(
        "function foo(...args){return args;} foo();", "foo", ImmutableSet.of("this", "args"));
  }

  private void assertArgMapHasKeys(String code, String fnName, ImmutableSet<String> expectedKeys) {
    Node n = parse(code);
    Node fn = findFunction(n, fnName);
    assertThat(fn).isNotNull();
    Node call = findCall(n, fnName);
    assertThat(call).isNotNull();
    ImmutableMap<String, Node> actualMap = getAndValidateFunctionCallParameterMap(fn, call);
    assertThat(actualMap.keySet()).isEqualTo(expectedKeys);
  }

  private void testNeededTemps(String code, String fnName, ImmutableSet<String> expectedTemps) {
    Node n = parse(code);
    Node fn = findFunction(n, fnName);
    assertThat(fn).isNotNull();
    Node call = findCall(n, fnName);
    assertThat(call).isNotNull();
    ImmutableMap<String, Node> args = getAndValidateFunctionCallParameterMap(fn, call);

    ImmutableSet<String> actualTemps =
        functionArgumentInjector.gatherCallArgumentsNeedingTemps(
            compiler, fn, args, ImmutableSet.of(), new ClosureCodingConvention());

    assertThat(actualTemps).isEqualTo(expectedTemps);
  }

  private ImmutableMap<String, Node> getAndValidateFunctionCallParameterMap(Node fn, Node call) {
    final ImmutableMap<String, Node> map =
        functionArgumentInjector.getFunctionCallParameterMap(fn, call, getNameSupplier());
    // Verify that all nodes in the map have source info, so they are valid to add to the AST
    for (Entry<String, Node> nameToNodeEntry : map.entrySet()) {
      final String name = nameToNodeEntry.getKey();
      final Node node = nameToNodeEntry.getValue();

      new SourceInfoCheck(compiler).setCheckSubTree(node);
      assertWithMessage("errors for name: %s", name).that(compiler.getErrors()).isEmpty();
    }

    return map;
  }

  private static Supplier<String> getNameSupplier() {
    return new Supplier<String>() {
      int i = 0;

      @Override
      public String get() {
        return String.valueOf(i++);
      }
    };
  }

  private static Node findCall(Node n, String name) {
    if (NodeUtil.isNormalOrOptChainCall(n)) {
      Node callee;
      if (NodeUtil.isNormalOrOptChainGetProp(n.getFirstChild())) {
        callee = n.getFirstFirstChild();
        // Only "call" is supported at this point.
        checkArgument(callee.getParent().getString().equals("call"));
      } else {
        callee = n.getFirstChild();
      }

      if (callee.isName() && callee.getString().equals(name)) {
        return n;
      }
    }

    for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
      Node result = findCall(c, name);
      if (result != null) {
        return result;
      }
    }

    return null;
  }

  private static Node findFunction(Node n, String name) {
    if (n.isFunction()) {
      if (n.getFirstChild().getString().equals(name)) {
        return n;
      }
    }

    for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
      Node result = findFunction(c, name);
      if (result != null) {
        return result;
      }
    }

    return null;
  }

  private Node parseFunction(String js) {
    return parse(js).getFirstChild();
  }

  private Node parse(String js) {
    Node n = compiler.parseTestCode(js);
    assertThat(compiler.getErrors()).isEmpty();
    return n;
  }
}
