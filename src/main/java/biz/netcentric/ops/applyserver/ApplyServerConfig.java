/*
 * (C) Copyright 2019 Netcentric, a Cognizant Digital Business.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.ops.applyserver;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.net.util.SubnetUtils.SubnetInfo;

/** Config parser for apply server using apache commons cli. */
public class ApplyServerConfig {

    static final Pattern EXCLUDE_FROM_FILTERING_REGEX_DEFAULT = Pattern.compile(".*\\.(properties|sh|so|jar|zip)$");
    static final Pattern EXCLUDE_FROM_DOWNLOAD_PATTERN_DEFAULT = Pattern
            .compile("((^|/)(bin|logs|modules|htdocs|apps|docs|include|lib|man|examples?|licenses?))|.*\\.(pid|jar|zip|log)");

    public static final String APPLY_SCRIPT_DEFAULT = "_apply.sh";

    private int serverPort;
    private String destination;
    private String script;
    private Pattern excludeFromFilteringRegex;
    private boolean filtering = true;
    private String pidFile;

    private String apiKey = null;
    private SubnetInfo ipRange = null;
    
    private boolean optionalPayload = false;
    private boolean disableUpload = false;

    private boolean enableDownload = false;
    private Pattern excludeFromDownloadPattern = EXCLUDE_FROM_DOWNLOAD_PATTERN_DEFAULT;

    private String propertiesFilename;

    private Map<String, String> commands = new TreeMap<>();

    private boolean isValid;

    public ApplyServerConfig(String[] args) {
        CommandLineParser parser = new DefaultParser();

        // create the Options
        Options options = new Options();
        options.addOption("p", "port", true, "the port the server is listening to");
        options.addOption("d", "destination", true, "the target destination dir. This is where payload is extracted to and relative script paths are evaluated against.");
        options.addOption("s", "script", true,
                "the script name to run (relative to destination dir after extracting) - defaults to \"" + APPLY_SCRIPT_DEFAULT
                        + "\". If a command is matched the command takes precedence. The script can already exist in the destination or be part of the uploaded package.");
        options.addOption("P", "properties", true,
                "the properties file name to apply the config  - if not given only env variables will be taken into account");
        options.addOption("nf", "no-filtering", false,
                "by default the incoming files are filtered using the OS env and '" + APPLY_SCRIPT_DEFAULT
                        + "' - using this option will disable that.");
        options.addOption("ef", "exclude-from-filtering", true,
                "to supply a regex of files to be explicitly excluded from filtering");

        options.addOption("pid", "pid-file", true,
                "will write the the pid file");

        options.addOption("k", "api-key", true,
                "when supplied, the given api key has to be sent with every request as header 'apikey'");

        options.addOption("ip", "ip-range", true,
                "when supplied, only upload/script execution requests from the given IP/IP range are accepted (can be regular IP like '20.30.40.50' or CIDR range like '20.30.40.50/24'). Useful to restrict clients that can make changes to the system. Has no effect for GET requests.");

        options.addOption("o", "optional-payload", false,
                "allows to not send a payload to be filtered/extracted with the request but just to run the scripped as mapped");

        options.addOption("du", "disable-upload", false,
                "disable upload (only allow script execution and potentially download if -ed is given)");

        Option commandOptions = new Option("c", "command", true,
                "allows to map URL paths to certain scripts: -c myscript=myscript.sh will run myscript.sh in desination upon POST requests to /myscript. Multiple -c options can be provided, the script provided with -s is the default if no command matches");
        options.addOption(commandOptions);

        options.addOption("dl", "enable-download", false,
                "enables download of the current files at destination via URL /download.tar.gz");
        options.addOption("ed", "exclude-from-download", true,
                "Regex for files to be excluded from download");

        try {
            // parse the command line arguments
            CommandLine line = parser.parse(options, args);

            if (line.hasOption("port")) {
                serverPort = Integer.parseInt(line.getOptionValue("port"));
            } else {
                throw new IllegalArgumentException("Parameter port is required");
            }
            if (line.hasOption("destination")) {
                destination = line.getOptionValue("destination");
            } else {
                throw new IllegalArgumentException("Parameter destination is required");
            }
            if (line.hasOption("script")) {
                script = line.getOptionValue("script");
            } else {
                script = APPLY_SCRIPT_DEFAULT;
            }

            if (line.hasOption("properties")) {
                propertiesFilename = line.getOptionValue("properties");
            } else {
                propertiesFilename = null;
            }

            if (line.hasOption("exclude-from-filtering")) {
                excludeFromFilteringRegex = getRegExPatternFromCommandLineOption(line, "exclude-from-filtering");
            } else {
                excludeFromFilteringRegex = EXCLUDE_FROM_FILTERING_REGEX_DEFAULT;
            }

            if (line.hasOption("no-filtering")) {
                filtering = false;
            }

            if (line.hasOption("pid-file")) {
                pidFile = line.getOptionValue("pid-file");
            }
            if (line.hasOption("api-key")) {
                apiKey = line.getOptionValue("api-key");
            }
            if (line.hasOption("ip-range")) {
                String ipRangeRaw = line.getOptionValue("ip-range");
                if(!ipRangeRaw.contains("/")) {
                	ipRangeRaw += "/32"; // use /32, the CIDR for the exact ip
                }
                SubnetUtils subnetUtils;
                try {
                	subnetUtils = new SubnetUtils(ipRangeRaw);
                } catch(IllegalArgumentException e) {
                	throw new IllegalArgumentException("Parameter --ip-range given with invalid value '"+ipRangeRaw+"': "+e, e);
                }
                subnetUtils.setInclusiveHostCount(true);
        		ipRange = subnetUtils.getInfo();
            }
            
            if (line.hasOption("optional-payload")) {
                optionalPayload = true;
            }
            if (line.hasOption("disable-upload")) {
                disableUpload = true;
            }

            if (line.hasOption("command")) {
                String[] optionValues = line.getOptionValues("command");

                for (String optionValue : optionValues) {
                    String[] bits = optionValue.split("=", 2);
                    if (bits.length < 2) {
                        System.err
                                .println("Invalid value for -c: " + optionValue + " (it needs to follow the syntax /myscript=myscript.sh)");
                        continue;
                    }
                    String path = bits[0];
                    String command = bits[1];
                    if (!path.startsWith("/")) {
                        path = "/" + path;
                    }
                    commands.put(path, command);
                }
            }

            if (line.hasOption("enable-download")) {
                enableDownload = true;
            }
            if (line.hasOption("exclude-from-download")) {
                excludeFromDownloadPattern = getRegExPatternFromCommandLineOption(line, "exclude-from-download");
            }

            isValid = true;

        } catch (Exception e) {
            isValid = false;
            System.out.println(e.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("apply-server", options);
        }
    }

	private Pattern getRegExPatternFromCommandLineOption(CommandLine line, String parameterName) {
		try {
			return Pattern.compile(line.getOptionValue(parameterName));
		} catch(PatternSyntaxException e) {
			throw new IllegalArgumentException("Invalid regex for parameter "+parameterName+": "+e.getMessage(), e);
		}
	}

    public boolean isValid() {
        return isValid;
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getDestination() {
        return destination;
    }

    public String getScript() {
        return script;
    }

    public Pattern getExcludeFromFilteringRegex() {
        return excludeFromFilteringRegex;
    }

    public boolean isFiltering() {
        return filtering;
    }

    public String getPidFile() {
        return pidFile;
    }

    public String getApiKey() {
        return apiKey;
    }
    
    public SubnetInfo getIpRange() {
		return ipRange;
	}

	public boolean isOptionalPayload() {
        return optionalPayload;
    }

    public boolean isDisableUpload() {
        return disableUpload;
    }

    public boolean isEnableDownload() {
        return enableDownload;
    }

    public Pattern getExcludeFromDownloadPattern() {
        return excludeFromDownloadPattern;
    }

    public String getPropertiesFilename() {
        return propertiesFilename;
    }

    public Map<String, String> getCommands() {
        return commands;
    }

}
