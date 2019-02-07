/*
 * (C) Copyright 2019 Netcentric, a Cognizant Digital Business.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.ops.applyserver;

import java.util.Date;

class ScriptResult {

    private final String scriptName;
    private final String result;
    private final int resultCode;
    private final Date time;
    private final String scriptOutput;

    public ScriptResult(String scriptName, String result, int resultCode, String scriptOutput) {
        this.scriptName = scriptName;
        this.result = result;
        this.resultCode = resultCode;
        this.time = new Date();
        this.scriptOutput = scriptOutput;
    }

	public String getScriptName() {
		return scriptName;
	}

	public String getResult() {
		return result;
	}

	public int getResultCode() {
		return resultCode;
	}

	public Date getTime() {
		return time;
	}

	public String getScriptOutput() {
		return scriptOutput;
	}
    
    
    
}