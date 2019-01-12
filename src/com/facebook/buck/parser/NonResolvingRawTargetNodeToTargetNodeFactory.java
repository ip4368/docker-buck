/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Creates {@link TargetNode} from {@link RawTargetNode} without resolving configurable attributes.
 *
 * <p>This is used to create {@link TargetNode} for targets that represent configuration rules.
 */
public class NonResolvingRawTargetNodeToTargetNodeFactory
    implements ParserTargetNodeFactory<RawTargetNode> {

  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ConstructorArgMarshaller marshaller;
  private final TargetNodeFactory targetNodeFactory;
  private final PackageBoundaryChecker packageBoundaryChecker;
  private final TargetNodeListener<TargetNode<?>> nodeListener;

  public NonResolvingRawTargetNodeToTargetNodeFactory(
      KnownRuleTypesProvider knownRuleTypesProvider,
      ConstructorArgMarshaller marshaller,
      TargetNodeFactory targetNodeFactory,
      PackageBoundaryChecker packageBoundaryChecker,
      TargetNodeListener<TargetNode<?>> nodeListener) {
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.marshaller = marshaller;
    this.targetNodeFactory = targetNodeFactory;
    this.packageBoundaryChecker = packageBoundaryChecker;
    this.nodeListener = nodeListener;
  }

  @Override
  public TargetNode<?> createTargetNode(
      Cell cell,
      Path buildFile,
      BuildTarget target,
      RawTargetNode rawTargetNode,
      Function<PerfEventId, Scope> perfEventScope) {

    BaseDescription<?> description =
        knownRuleTypesProvider.get(cell).getDescription(rawTargetNode.getRuleType());
    Cell targetCell = cell.getCell(target);

    ImmutableMap<String, Object> populatedAttributes;
    try (SimplePerfEvent.Scope scope =
        perfEventScope.apply(PerfEventId.of("MarshalledConstructorArg.convertRawAttributes"))) {
      populatedAttributes =
          marshaller.convertRawAttributes(
              targetCell.getCellPathResolver(),
              targetCell.getFilesystem(),
              target,
              description.getConstructorArgType(),
              rawTargetNode.getAttributes().getAll());
    } catch (CoerceFailedException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }

    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    Object constructorArg =
        marshaller.populateFromConfiguredAttributes(
            cell.getCellPathResolver(),
            target,
            description.getConstructorArgType(),
            declaredDeps,
            assertRawTargetNodeAttributesNotConfigurable(target, populatedAttributes));

    TargetNode<?> targetNode =
        targetNodeFactory.createFromObject(
            description,
            constructorArg,
            targetCell.getFilesystem(),
            target,
            declaredDeps.build(),
            rawTargetNode.getVisibilityPatterns(),
            rawTargetNode.getWithinViewPatterns(),
            targetCell.getCellPathResolver());

    packageBoundaryChecker.enforceBuckPackageBoundaries(targetCell, target, targetNode.getInputs());

    try {
      nodeListener.onCreate(buildFile, targetNode);
    } catch (IOException e) {
      throw new HumanReadableException(e.getMessage(), e);
    }

    return targetNode;
  }

  private ImmutableMap<String, Object> assertRawTargetNodeAttributesNotConfigurable(
      BuildTarget buildTarget, ImmutableMap<String, Object> rawTargetNodeAttributes) {
    for (Map.Entry<String, ?> entry : rawTargetNodeAttributes.entrySet()) {
      Preconditions.checkState(
          !(entry.getValue() instanceof SelectorList),
          "Attribute %s cannot be configurable in %s",
          entry.getKey(),
          buildTarget);
    }
    return rawTargetNodeAttributes;
  }
}
