/*
 * (C) Copyright 2019 Netcentric, a Cognizant Digital Business.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.ops.applyserver;

class NoFilesInRequestBodyException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public NoFilesInRequestBodyException(String s) {
        super(s);
    }

}