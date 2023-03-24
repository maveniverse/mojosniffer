/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.cstamas.maven.components.mojosniffer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;

/**
 * Mojo sniffer participant that listens for session begins and ends.
 */
@Named
@Singleton
public class MojoSnifferLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    private final MojoSniffer mojoSniffer;

    @Inject
    public MojoSnifferLifecycleParticipant(MojoSniffer mojoSniffer) {
        this.mojoSniffer = mojoSniffer;
    }

    @Override
    public void afterSessionStart(MavenSession session) {
        mojoSniffer.sessionStarted(session);
    }

    @Override
    public void afterSessionEnd(MavenSession session) {
        mojoSniffer.sessionStopped(session);
    }
}
