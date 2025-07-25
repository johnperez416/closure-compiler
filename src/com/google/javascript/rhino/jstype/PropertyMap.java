/*
 *
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Rhino code, released
 * May 6, 1999.
 *
 * The Initial Developer of the Original Code is
 * Netscape Communications Corporation.
 * Portions created by the Initial Developer are Copyright (C) 1997-1999
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Nick Santos
 *   Google Inc.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * the GNU General Public License Version 2 or later (the "GPL"), in which
 * case the provisions of the GPL are applicable instead of those above. If
 * you wish to allow use of your version of this file only under the terms of
 * the GPL and not to allow others to use your version of this file under the
 * MPL, indicate your decision by deleting the provisions above and replacing
 * them with the notice and other provisions required by the GPL. If you do
 * not delete the provisions above, a recipient may use your version of this
 * file under either the MPL or the GPL.
 *
 * ***** END LICENSE BLOCK ***** */

package com.google.javascript.rhino.jstype;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.javascript.rhino.jstype.Property.OwnedProperty;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** Representation for a collection of properties on an object. */
final class PropertyMap {
  private static final PropertyMap EMPTY_MAP =
      new PropertyMap(
          ImmutableMap.<String, Property>of(), ImmutableMap.<KnownSymbolType, Property>of());

  // A place to get the inheritance structure.
  // Because the extended interfaces are resolved dynamically, this gets
  // messy :(. If type-resolution was more well-defined, we could
  // just reference primary parents and secondary parents directly.
  private @Nullable ObjectType parentSource = null;

  // The map of our own properties.
  private final Map<String, Property> properties;

  private Map<KnownSymbolType, Property> knownSymbols; // lazily initialized

  /**
   * The set of keys for this map and its ancestors.
   *
   * <p>Collecting the set of properties turns out to be expensive for some high volume callers
   * (e.g. structural type equality). Since the results don't change often, this cache eliminates
   * most of the cost.
   */
  private @Nullable ImmutableSortedSet<String> cachedKeySet = null;

  /**
   * The set of known symbols keys for this map and its ancestors.
   *
   * <p>Collecting the set of properties turns out to be expensive for some high volume callers
   * (e.g. structural type equality). Since the results don't change often, this cache eliminates
   * most of the cost.
   */
  private ImmutableSet<KnownSymbolType> cachedKnownSymbolsKeySet = null;

  /**
   * A "timestamp" for map mutations to validate {@link #cachedKeySet} and {@link
   * #cachedKnownSymbolsKeySet}.
   *
   * <p>If this value is less than the counter in any ancestor map, the cache is invalid. The update
   * algorithm is similar to using a global counter, but uses a distributed approach that prevents
   * the need for a single source of truth.
   */
  private int cachedKeySetCounter = 0;

  PropertyMap() {
    this(new TreeMap<>(), null);
  }

  private PropertyMap(
      Map<String, Property> underlyingMap, Map<KnownSymbolType, Property> underlyingknownSymbols) {
    this.properties = underlyingMap;
    this.knownSymbols = underlyingknownSymbols;
  }

  static PropertyMap immutableEmptyMap() {
    return EMPTY_MAP;
  }

  void setParentSource(ObjectType ownerType) {
    if (this == EMPTY_MAP) {
      return;
    }

    this.parentSource = ownerType;
    this.incrementCachedKeySetCounter();
  }

  @VisibleForTesting
  void setParentForTesting(PropertyMap parent) {
    this.parentSource = parent.parentSource;
    this.incrementCachedKeySetCounter();
  }

  /** Returns the direct parent of this property map. */
  @Nullable PropertyMap getPrimaryParent() {
    if (parentSource == null) {
      return null;
    }
    ObjectType iProto = parentSource.getImplicitPrototype();
    return iProto == null ? null : iProto.getPropertyMap();
  }

  /**
   * Returns the secondary parents of this property map, for interfaces that need multiple
   * inheritance or for interfaces of abstract classes.
   */
  private Iterable<ObjectType> getSecondaryParentObjects() {
    if (parentSource == null) {
      return ImmutableList.of();
    }
    if (parentSource.getConstructor() != null && parentSource.getConstructor().isAbstract()) {
      return parentSource.getConstructor().getOwnImplementedInterfaces();
    }
    return parentSource.getCtorExtendedInterfaces();
  }

  @Nullable OwnedProperty findClosest(String name) {
    return findClosest(new Property.StringKey(name));
  }

  @Nullable OwnedProperty findClosest(Property.Key name) {
    // Check primary parents which always has precendence over secondary.
    for (PropertyMap map = this; map != null; map = map.getPrimaryParent()) {
      Property prop = map.getOwnProperty(name);
      if (prop != null) {
        return new OwnedProperty(map.parentSource, prop);
      }
    }

    // Recurse into secondary parents.
    for (PropertyMap map = this; map != null; map = map.getPrimaryParent()) {
      for (ObjectType o : map.getSecondaryParentObjects()) {
        PropertyMap parent = o.getPropertyMap();
        if (parent != null) {
          OwnedProperty e = parent.findClosest(name);
          if (e != null) {
            return e;
          }
        }
      }
    }

    return null;
  }

  Property getOwnProperty(String propertyName) {
    return properties.get(propertyName);
  }

  @Nullable Property getOwnProperty(Property.Key propertyName) {
    return switch (propertyName.kind()) {
      case STRING -> getOwnProperty(propertyName.string());
      case SYMBOL -> knownSymbols != null ? knownSymbols.get(propertyName.symbol()) : null;
    };
  }

  int getPropertiesCount() {
    PropertyMap primaryParent = getPrimaryParent();
    if (primaryParent == null) {
      return this.properties.size();
    }

    return this.getAllKeys().stringKeys().size();
  }

  Set<String> getOwnPropertyNames() {
    return properties.keySet();
  }

  Set<KnownSymbolType> getOwnKnownSymbols() {
    return knownSymbols != null ? knownSymbols.keySet() : ImmutableSet.of();
  }

  public AllKeys getAllKeys() {
    LinkedHashSet<PropertyMap> ancestors = new LinkedHashSet<>();
    this.collectAllAncestors(ancestors);

    int maxAncestorCounter = 0;
    for (PropertyMap ancestor : ancestors) {
      if (ancestor.cachedKeySetCounter > maxAncestorCounter) {
        maxAncestorCounter = ancestor.cachedKeySetCounter;
      }
    }

    /*
     * If any counter is greater than this counter, there has been a mutation and the cache must be
     * rebuilt.
     */
    if (maxAncestorCounter != this.cachedKeySetCounter || this.cachedKeySet == null) {
      TreeSet<String> keys = new TreeSet<>();
      Set<KnownSymbolType> knownSymbolsKeys = new LinkedHashSet<>();
      for (PropertyMap ancestor : ancestors) {
        /*
         * Update the counters in all ancestors.
         *
         * <p>This update scheme is convergent. As long as there are no mutations, calls {@link
         * #keySet} will eventually set stable caches on all maps.
         */
        ancestor.cachedKeySetCounter = maxAncestorCounter;
        ancestor.cachedKeySet = null;
        ancestor.cachedKnownSymbolsKeySet = null;

        keys.addAll(ancestor.getOwnPropertyNames());
        knownSymbolsKeys.addAll(ancestor.getOwnKnownSymbols());
      }
      this.cachedKeySet = ImmutableSortedSet.copyOfSorted(keys);
      this.cachedKnownSymbolsKeySet = ImmutableSet.copyOf(knownSymbolsKeys);
    }

    return new AllKeys(this.cachedKeySet, this.cachedKnownSymbolsKeySet);
  }

  public record AllKeys(
      ImmutableSortedSet<String> stringKeys, Set<KnownSymbolType> knownSymbolKeys) {}

  private void collectAllAncestors(LinkedHashSet<PropertyMap> ancestors) {
    if (!ancestors.add(this)) {
      return;
    }

    PropertyMap primaryParent = this.getPrimaryParent();
    if (primaryParent != null) {
      primaryParent.collectAllAncestors(ancestors);
    }

    for (ObjectType parentType : this.getSecondaryParentObjects()) {
      PropertyMap parentMap = parentType.getPropertyMap();
      if (parentMap != null) {
        parentMap.collectAllAncestors(ancestors);
      }
    }
  }

  void putProperty(String name, Property newProp) {
    Property oldProp = properties.get(name);

    if (oldProp == null) {
      // The cache is only invalidated if this is a new property name.
      this.incrementCachedKeySetCounter();
    } else {
      // This is to keep previously inferred JsDoc info, e.g., in a replaceScript scenario.
      newProp.setJSDocInfo(oldProp.getJSDocInfo());
    }

    properties.put(name, newProp);
  }

  void putProperty(KnownSymbolType symbol, Property newProp) {
    Property oldProp = knownSymbols != null ? knownSymbols.get(symbol) : null;

    if (oldProp == null) {
      // The cache is only invalidated if this is a new property name.
      this.incrementCachedKeySetCounter();
    }

    if (knownSymbols == null) {
      knownSymbols = new LinkedHashMap<>();
    }
    knownSymbols.put(symbol, newProp);
  }

  void putProperty(Property.Key name, Property newProp) {
    switch (name.kind()) {
      case STRING -> putProperty(name.string(), newProp);
      case SYMBOL -> putProperty(name.symbol(), newProp);
    }
  }

  Iterable<Property> values() {
    return properties.values();
  }

  @Override
  public int hashCode() {
    // We need to override hashCode so that JSType.hashCode is consistent. JSType uses the hashcode
    // of its PropertyMap. Otherwise PropertyMap uses identity equality.
    //
    // Calculate the hash just based on the property names, not their types.
    // Otherwise we can get into an infinite loop because the ObjectType hashCode
    // method calls this one.
    return this.knownSymbols == null
        ? Objects.hashCode(this.properties.keySet())
        : Objects.hash(this.properties.keySet(), this.knownSymbols.keySet());
  }

  private void incrementCachedKeySetCounter() {
    this.cachedKeySetCounter++;
    this.cachedKeySet = null;

    checkState(this.cachedKeySetCounter >= 0);
  }
}
