Apply Server
================================================

# Overview

The idea behind "Apply Server" is to control the server via http (instead of ssh). Use cases are 

* Deploy configuration files and restart services (e.g. httpd)
* Run maintenance scripts 

Apply Server was inspired by the config via http approach of nginx unit [1] and allows to turn apache, solr, or any other server into a http-configurable by just starting another process that acts as agent on target system. **Using the Apply Server, it is easy to deploy well-versioned configuration artifacts to target systems via a CI/CD pipeline** (like e.g. Jenkins). Obviously CI/CD tools also support ssh, but ssh access can be harder to obtain than opening an http port, also ssh often does not make it easy to transfer/unpack configuration tars (often a change of user is required, permissions can be a challenge). In contrary, an Apply Server can just be started with the same user as the service is running (hence all correct permissions are automatically in placed) and the start parameters ensure its actions are limited to what the Apply Server was started with.

## Why not use Puppet/Chef/Ansible?

In general all these tools are very useful (e.g. even to install the apply server agent, see "Setup Apply Server via puppet" below), however their strength is more in setting up a base configuration (OS level, files, directories, permissions, services etc.). For deployments of constantly changing code/configuration they lack support for a workflow where any people (including non-technical people) can make code/configuration travel across environments - here CI/CD severs like Jenkins have their strength. Many tool allow for deployment via http (e.g. Tomcat, Adobe AEM, etc.), Apply Server makes it easy to deploy Apache/Varnish/etc. configuration in the same way as software.

## Why another http server? 

There are many options for starting an http server already [2], but none that focus on

* Running scripts on target system (and make the result history visible)
* Upload/download files to/from a target directory in zip/tgz format (zipping/unzipping on the fly)
* Have minimal dependencies (Apply Server is a lightweight fat jar and has only a JRE as dependency)


[1] https://www.nginx.com/blog/nginx-unit-1-0-released/
https://www.infoq.com/news/2018/05/nginx-unit-dynamic-web-server

[2] https://gist.github.com/willurd/5720255

# Security

There are two options to lock down access directly in apply server:

* IP restriction: Only POST requests from given IP/IP range are accepted
* API Key Secret: Only requests that carry the correct API key as header are accepted

Generally security restrictions are given as start parameters of the server. Additionally if needed, also the firewall of the production systems can be configured to allow only requests from certain IPs.

# Size and dependencies

Apply server is very lightweight: It is just ~130 KB of size while all dependencies are included in the jar directly (e.g. commons-cli) and only the actual required classes are kept in the file (proguard-maven-plugin is used for shrinking). 

Apply server is a simple jar that can be run via `java -jar` on JREs > 1.8 and has no other dependencies.

# Usage

## Start Server on target system

```
$ java -jar apply-server-1.5.0.jar
usage: apply-server
 -c,--command <arg>                   allows to map URL paths to certain
                                      scripts: -c myscript=myscript.sh
                                      will run myscript.sh in desination
                                      upon POST requests to /myscript.
                                      Multiple -c options can be provided,
                                      the script provided with -s is the
                                      default if no command matches
 -d,--destination <arg>               the target destination dir. This is
                                      where payload is extracted to and
                                      relative script paths are evaluated
                                      against.
 -dl,--enable-download                enables download of the current
                                      files at destination via URL
                                      /download.tar.gz
 -du,--disable-upload                 disable upload (only allow script
                                      execution and potentially download
                                      if -ed is given)
 -ed,--exclude-from-download <arg>    Regex for files to be excluded from
                                      download
 -ef,--exclude-from-filtering <arg>   to supply a regex of files to be
                                      explicitly excluded from filtering
 -ip,--ip-range <arg>                 when supplied, only upload/script
                                      execution requests from the given
                                      IP/IP range are accepted (can be
                                      regular IP like '20.30.40.50' or
                                      CIDR range like '20.30.40.50/24').
                                      Useful to restrict clients that can
                                      make changes to the system. Has no
                                      effect for GET requests.
 -k,--api-key <arg>                   when supplied, the given api key has
                                      to be sent with every request as
                                      header 'apikey'
 -nf,--no-filtering                   by default the incoming files are
                                      filtered using the OS env and
                                      '_apply.sh' - using this option will
                                      disable that.
 -o,--optional-payload                allows to not send a payload to be
                                      filtered/extracted with the request
                                      but just to run the scripped as
                                      mapped
 -p,--port <arg>                      the port the server is listening to
 -P,--properties <arg>                the properties file name to apply
                                      the config  - if not given only env
                                      variables will be taken into account
 -pid,--pid-file <arg>                will write the the pid file
 -s,--script <arg>                    the script name to run (relative to
                                      destination dir after extracting) -
                                      defaults to "_apply.sh". If a
                                      command is matched the command takes
                                      precedence. The script can already
                                      exist in the destination or be part
                                      of the uploaded package.
```

# Examples
## Starting Apply Server Agent

### start server to take and unpack zip, filter it and run an apply script afterwards:

```
java -jar apply-server-1.5.0.jar -p 448 -d /path/to/destination -s applyConfig.sh --api-key MT7HpOKnx5 # secured via api key secret
java -jar apply-server-1.5.0.jar -p 448 -d /path/to/destination -s applyConfig.sh --ip-range 100.200.300.0/16 # secured via ip range protection

```

### start a server to run scripts remotely

```
java -jar apply-server-1.5.0.jar -p 448 -d /path/to/scripts --optional-payload -c /script1=myScript1.sh -c /script2=myScript2.sh --api-key MT7HpOKnx5  # secured via api key secret
# alternatively (or in combination) use --ip-range 100.200.300.0/16 to secure via ip range protection

```

## Client Usage
Push configurations to it from elsewhere and run a script to use the new files

To push configurations to the target system and run the script as provided by server start:

#### upload and run default script

```
curl -X POST -H "apikey: MT7HpOKnx5" --data-binary "@path/to/my-config-package.tar.gz" http://myserver:448/package-name.tar.gz
```

#### or format parameter can be given to not include the filename in path

```
curl -X POST -H "apikey: MT7HpOKnx5" --data-binary "@path/to/my-config-package.tar.gz" http://myserver:448?format=tar.gz
```

## Running scripts only
No upload required, often used along with multiple -c parameters

if the server was stared with

```
java -jar apply-server-1.5.0.jar -d /path/to/scripts --optional-payload -c /script1=myScript1.sh -c /script2=myScript2.sh ...
```

the following two commands will run myScript1.sh or myScript2.sh respectively:

```
curl -X POST -H "apikey: MT7HpOKnx5" http://myserver:448/script1
```
```
curl -X POST -H "apikey: MT7HpOKnx5" http://myserver:448/script2
```

## Listing past executions (via GET)
Just calling `http://myserver:448` in browser will list all past executions and give links to see the logs of each execution.

# Setup Apply Server via puppet
The following snippet will download and start the apply server with the given arguments:

```
### Parameters ###
$apply_server_version = '1.5.0'
$apply_server_target_dir = '/opt/files'
$apply_server_port = 8089
$apply_server_additional_arguments = ' -du -dl'
$apply_server_dir = '/opt/apply-server'
$nexus_base_url = 'https://repo.int.netcentric.biz/nexus/service/local/repositories'
 
### Provision the jar ###
$apply_server_path = "${apply_server_dir}/apply-server-${apply_server_version}.jar"
notice("Ensuring apply server exists: ${apply_server_version}")
file { $apply_server_dir:
  ensure  => directory,
}
 
$curl_command = "/usr/bin/curl -u ${netcentric::maven::nexus_user}:${netcentric::maven::nexus_password} -o ${apply_server_path} ${nexus_base_url}/netcentric-releases/content/biz/netcentric/ops/applyserver/apply-server/${apply_server_version}/apply-server-${apply_server_version}.jar"
exec { 'retrieve_apply_server_jar':
  command => "/usr/bin/echo Downloading ${apply_server_path} && ${curl_command}",
  creates => $apply_server_path,
  require => [ File[$apply_server_dir] ],
}
 
file { $apply_server_path:
  ensure  => file,
  require => [ Exec["retrieve_apply_server_jar"] ],
}
 
### Run the service ###
# using the base command with port allows to run multiple servers
$base_command = "java -jar ${apply_server_path} -p ${apply_server_port}"
service { "apply-server-${apply_server_port}":
  ensure  => running,
  start   => "/usr/bin/nohup ${base_command} -d ${apply_server_target_dir} ${apply_server_additional_arguments} < /dev/null > ${apply_server_dir}/apply-server.log 2>&1 &",
  pattern => $base_command,
  require => [ File[$apply_server_path] ],
}  
```
