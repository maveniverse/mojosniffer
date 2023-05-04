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
package eu.maveniverse.maven.extensions.mojosniffer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.junit.jupiter.api.Test;

public class MojoSnifferTest {
    MojoSniffer mojoSniffer = new MojoSniffer();

    @Test
    public void mavenCompat() {
        assertFalse(mojoSniffer.isMavenCompatClass(getClass()));
        assertTrue(mojoSniffer.isMavenCompatClass(ArtifactResolver.class));
    }

    @Test
    public void deprecation() {
        assertFalse(mojoSniffer.isDeprecatedClass(getClass()));
        assertFalse(mojoSniffer.isDeprecatedClass(IA.class));
        assertTrue(mojoSniffer.isDeprecatedClass(IB.class));
        assertFalse(mojoSniffer.isDeprecatedClass(IC.class));

        assertFalse(mojoSniffer.isDeprecatedClass(CA.class));
        assertFalse(mojoSniffer.isDeprecatedClass(CB.class));
        assertTrue(mojoSniffer.isDeprecatedClass(CC.class));
    }

    private interface IA {}

    @Deprecated
    private interface IB {}

    private interface IC {}

    private static class CA implements IA {}

    private static class CB extends CA implements IB {}

    @Deprecated
    private static class CC implements IC {}
}
