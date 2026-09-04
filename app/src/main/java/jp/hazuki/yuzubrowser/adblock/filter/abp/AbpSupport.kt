/*
 * Copyright (C) 2026 Scriptoria
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

package jp.hazuki.yuzubrowser.adblock.filter.abp

/**
 * The vendored engine expects these to be supplied by the embedding app; upstream they lived on
 * the host browser's blocker manager. Keeping them next to the ABP_PREFIX_* constants they are
 * built from means the prefixes can stay `internal`.
 */

/** Every filter bucket that is written to its own file and loaded into its own container. */
val blockerPrefixes = listOf(
    ABP_PREFIX_ALLOW,
    ABP_PREFIX_DENY,
    ABP_PREFIX_MODIFY,
    ABP_PREFIX_MODIFY_EXCEPTION,
    ABP_PREFIX_IMPORTANT,
    ABP_PREFIX_IMPORTANT_ALLOW,
    ABP_PREFIX_REDIRECT,
    ABP_PREFIX_REDIRECT_EXCEPTION,
)

/** $badfilter counterparts, which cancel a matching filter from any list. */
val badfilterPrefixes = blockerPrefixes.map { ABP_PREFIX_BADFILTER + it }

/**
 * Modify buckets carry a ModifyFilter payload alongside the pattern, so they are serialised and
 * read back with a different record layout than plain block/allow filters.
 */
fun isModify(prefix: String) = prefix in listOf(
    ABP_PREFIX_MODIFY,
    ABP_PREFIX_MODIFY_EXCEPTION,
    ABP_PREFIX_REDIRECT,
    ABP_PREFIX_REDIRECT_EXCEPTION,
)

/** File prefix for compiled "+js(...)" scriptlet rules, alongside ABP_PREFIX_ELEMENT. */
const val ABP_PREFIX_SCRIPTLET = "sj_"

// uBlock Origin resource names, referenced by the $empty and $mp4 filter options.
const val RES_EMPTY = "empty"
const val RES_NOOP_MP4 = "noopmp4-1s"
