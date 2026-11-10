/*
 * Copyright 2026 Google LLC
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

package io.github.glaforge.fixslidepic.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ProgressEvent(
        String type,
        String sessionId,
        int iteration,
        int maxIterations,
        String message,
        IterationResult iterationResult,
        SlideSessionResult sessionResult
) {
}
