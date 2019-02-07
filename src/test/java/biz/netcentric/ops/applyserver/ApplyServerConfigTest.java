/*
 * (C) Copyright 2019 Netcentric, a Cognizant Digital Business.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.ops.applyserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ApplyServerConfigTest {

    @Test
    public void testWithoutParameters() {
        ApplyServerConfig applyServerConfig = new ApplyServerConfig(new String[] {});
        assertFalse(applyServerConfig.isValid());
    }

    @Test
    public void testWithCorrectParameters() {
        ApplyServerConfig applyServerConfig = new ApplyServerConfig("-d /test -p 3000".split(" "));
        assertTrue(applyServerConfig.isValid());
        assertEquals("/test", applyServerConfig.getDestination());
        assertEquals(3000, applyServerConfig.getServerPort());
    }

}
