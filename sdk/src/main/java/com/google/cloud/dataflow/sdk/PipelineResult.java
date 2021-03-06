/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk;

import com.google.cloud.dataflow.sdk.runners.AggregatorRetrievalException;
import com.google.cloud.dataflow.sdk.runners.AggregatorValues;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;

/**
 * Result of {@link com.google.cloud.dataflow.sdk.Pipeline#run()}.
 */
public interface PipelineResult {

  /**
   * Retrieves the current state of the pipeline execution.
   *
   * @return the {@link State} representing the state of this pipeline.
   */
  State getState();

  /**
   * Retrieves the current value of the provided {@link Aggregator}.
   *
   * @param aggregator the Aggregator to retrieve values for
   * @return the current values of the aggregator, which may be empty if there are no values yet
   * @throws AggregatorRetrievalException if the aggregator values could not be retrieved
   */
  <T> AggregatorValues<T> getAggregatorValues(Aggregator<?, T> aggregator)
      throws AggregatorRetrievalException;

  // TODO: method to retrieve error messages.

  /** Named constants for common values for the job state. */
  public enum State {
    /** The job state could not be obtained or was not specified. */
    UNKNOWN(false),
    /** The job has been paused, or has not yet started. */
    STOPPED(false),
    /** The job is currently running. */
    RUNNING(false),
    /** The job has successfully completed. */
    DONE(true),
    /** The job has failed. */
    FAILED(true),
    /** The job has been explicitly cancelled. */
    CANCELLED(true);

    private final boolean terminal;

    private State(boolean terminal) {
      this.terminal = terminal;
    }

    /**
     * Returns if the job state can no longer complete work.
     *
     * @return if this JobState represents a terminal state.
     */
    public final boolean isTerminal() {
      return terminal;
    }

  }


}
