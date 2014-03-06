Neo4jAdminServlet
=================

A Servlet allowing to control a Neo4J instance using JSON RPC (ajax/javascript). This uses Spring and Neo4jTemplate to communicate with a db instance and jquery for a dynamic console to embed. This is very leight-weight, but allows almost full control over your neo4j.

Its purpose is to be included within a neo4j based web application, that wants to easily debug without having to open the [neoj console](http://console.neo4j.org/) explicitly.

It allows traversing a neo4j tree by using command line parameters like:

- ls
- cd [nodeId]
- set ATTRIBUTE = value

# Requirements

- spring-web: for access to access controller bean
- spring-data-neo4j: Uses Spring for NEO4J and
- jackson: for the RPC binding

The user interface is handled by [jquery.terminal](https://github.com/jcubic/jquery.terminal)

# Usage

Download the jar and include it in your project.

## Servlet Integration
Add the servlets into your web.xml:

	<?xml version="1.0" encoding="UTF-8"?>
	<web-app version="2.4" xmlns="http://java.sun.com/xml/ns/j2ee" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://java.sun.com/xml/ns/j2ee http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd">
	...
		<servlet>
			<servlet-name>Neo4JAdminConsole</servlet-name>
			<servlet-class>com.corinis.neo4jadmin.servlet.NeoAdmin</servlet-class>
			<init-param>
				<!--
					Automatically create a Session within the servlet
				-->
				<param-name>autoCreateSession</param-name>
				<param-value>true</param-value>
			</init-param>
			<init-param>
				<!--
					get the spring bean that acts as an access controller
				-->
				<param-name>accessControllerBean</param-name>
				<param-value>auto</param-value> <!-- or name of bean -->
			</init-param>

		</servlet>
	...
		<servlet-mapping>
			<servlet-name>Neo4JAdminConsole</servlet-name>
			<url-pattern>/ajax/console</url-pattern>
		</servlet-mapping>
	...
	</web-app>

Note:
If you do not want/need an access controller (because you use the console for debugging only) - just remove the accessControllerBean init parameter. Combined with autoCreateSession

## UI Integration

You can simply add the [https://github.com/corinis/Neo4jAdminServlet/tree/master/src/main/webapp/console](html/js) files to your webapp (it does NOT need any dynamic jsp or other component). 

To manually do it:
First download the terminal library from [jquery.terminal](https://github.com/jcubic/jquery.terminal).

Integrate it:
	<script src="http://code.jquery.com/jquery-1.11.0.min.js"></script>
	<script src="js/jquery.mousewheel-min.js"></script>
    <script src="js/jquery.terminal-0.8.2.min.js"></script>
	<link rel="stylesheet" type="text/css" href="js/jquery.terminal.css" />

Initialize the temrminal with collowing code (adapt the div id and the url of the console as defined in the webapp):
		var term = $("#myTerminalDiv").terminal("/ajax/console", {
			prompt: '>', 
			name: 'console',
			greetings: "Start browsing in neo4j. For usage and commands type 'help'."
		});


It can be easily integrated using jquery ui to be in a floating window - instead of inline.


## Maven


# Simple Access Controller

This is a simple access controller, that just requres a session with a "username" attribute that is not "anonymous". The login for this can be 
handled anywhere else (i.e. a jsp):

	@Service("neo4JAdminAcccessController)
	public class Neo4jAdminAccessControllerImpl implements Neo4jAdminAccessController {

		public boolean canAccess(HttpSession session) {
			if(session == null)
				return false;
			if(session.getAttribute("username") == null || "anonymous".equals(session.getAttribute("username")))
				return false;
			return false;
		}

		public boolean canExecute(HttpSession session, final RPC rpc, long curNode) {
			// if there is a session - just execute anything
			return true;
		}
	}


