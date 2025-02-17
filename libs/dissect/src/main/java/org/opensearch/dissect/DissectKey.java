/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.dissect;

import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>A Key of a dissect pattern. This class models the name and modifiers and provides some validation.</p>
 * <p>For dissect pattern of {@code %{a} %{+a} %{b}} the dissect keys are:
 * <ul>
 * <li>{@code a}</li>
 * <li>{@code +a}</li>
 * <li>{@code b}</li>
 * </ul>
 * This class represents a single key.
 * <p>A single key is composed of a name and it's modifiers. For the key {@code +a}, {@code a} is the name and {@code +} is the modifier.
 * @see DissectParser
 */
public final class DissectKey {
    private static final Pattern LEFT_MODIFIER_PATTERN = Pattern.compile("([+*&?])(.*?)(->)?$", Pattern.DOTALL);
    private static final Pattern RIGHT_PADDING_PATTERN = Pattern.compile("^(.*?)(->)?$", Pattern.DOTALL);
    private static final Pattern APPEND_WITH_ORDER_PATTERN = Pattern.compile("[+](.*?)(/)([0-9]+)(->)?$", Pattern.DOTALL);
    private final Modifier modifier;
    private boolean skip;
    private boolean skipRightPadding;
    private int appendPosition;
    private String name;

    /**
     * Constructor - parses the String key into it's name and modifier(s)
     *
     * @param key The key without the leading <code>%{</code> or trailing <code>}</code>, for example {@code a->}
     */
    DissectKey(String key) {
        skip = key == null || key.isEmpty();
        modifier = Modifier.findModifier(key);
        switch (modifier) {
            case NONE:
                Matcher matcher = RIGHT_PADDING_PATTERN.matcher(key);
                while (matcher.find()) {
                    name = matcher.group(1);
                    skipRightPadding = matcher.group(2) != null;
                }
                skip = name.isEmpty();
                break;
            case NAMED_SKIP:
                matcher = LEFT_MODIFIER_PATTERN.matcher(key);
                while (matcher.find()) {
                    name = matcher.group(2);
                    skipRightPadding = matcher.group(3) != null;
                }
                skip = true;
                break;
            case APPEND:
                matcher = LEFT_MODIFIER_PATTERN.matcher(key);
                while (matcher.find()) {
                    name = matcher.group(2);
                    skipRightPadding = matcher.group(3) != null;
                }
                break;
            case FIELD_NAME:
                matcher = LEFT_MODIFIER_PATTERN.matcher(key);
                while (matcher.find()) {
                    name = matcher.group(2);
                    skipRightPadding = matcher.group(3) != null;
                }
                break;
            case FIELD_VALUE:
                matcher = LEFT_MODIFIER_PATTERN.matcher(key);
                while (matcher.find()) {
                    name = matcher.group(2);
                    skipRightPadding = matcher.group(3) != null;
                }
                break;
            case APPEND_WITH_ORDER:
                matcher = APPEND_WITH_ORDER_PATTERN.matcher(key);
                while (matcher.find()) {
                    name = matcher.group(1);
                    appendPosition = Short.valueOf(matcher.group(3));
                    skipRightPadding = matcher.group(4) != null;
                }
                break;
        }

        if (name == null || (name.isEmpty() && !skip)) {
            throw new DissectException.KeyParse(key, "The key name could be determined");
        }
    }

    /**
     * Copy constructor to explicitly override the modifier.
     * @param key The key to copy (except for the modifier)
     * @param modifier the modifer to use for this copy
     */
    DissectKey(DissectKey key, DissectKey.Modifier modifier){
        this.modifier = modifier;
        this.skipRightPadding = key.skipRightPadding;
        this.skip = key.skip;
        this.name = key.name;
        this.appendPosition = key.appendPosition;
    }

    Modifier getModifier() {
        return modifier;
    }

    boolean skip() {
        return skip;
    }

    boolean skipRightPadding() {
        return skipRightPadding;
    }

    int getAppendPosition() {
        return appendPosition;
    }

    String getName() {
        return name;
    }

    //generated
    @Override
    public String toString() {
        return "DissectKey{" +
            "modifier=" + modifier +
            ", skip=" + skip +
            ", appendPosition=" + appendPosition +
            ", name='" + name + '\'' +
            '}';
    }

    public enum Modifier {
        NONE(""), APPEND_WITH_ORDER("/"), APPEND("+"), FIELD_NAME("*"), FIELD_VALUE("&"), NAMED_SKIP("?");

        private static final Pattern MODIFIER_PATTERN = Pattern.compile("[/+*&?]");

        private final String modifier;

        @Override
        public String toString() {
            return modifier;
        }

        Modifier(final String modifier) {
            this.modifier = modifier;
        }

        //package private for testing
        static Modifier fromString(String modifier) {
            return EnumSet.allOf(Modifier.class).stream().filter(km -> km.modifier.equals(modifier))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Found invalid modifier.")); //throw should never happen
        }

        private static Modifier findModifier(String key) {
            Modifier modifier = Modifier.NONE;
            if (key != null && !key.isEmpty()) {
                Matcher matcher = MODIFIER_PATTERN.matcher(key);
                int matches = 0;
                while (matcher.find()) {
                    Modifier priorModifier = modifier;
                    modifier = Modifier.fromString(matcher.group());
                    if (++matches > 1 && !(APPEND.equals(priorModifier) && APPEND_WITH_ORDER.equals(modifier))) {
                        throw new DissectException.KeyParse(key, "multiple modifiers are not allowed.");
                    }
                }
            }
            return modifier;
        }
    }
}
