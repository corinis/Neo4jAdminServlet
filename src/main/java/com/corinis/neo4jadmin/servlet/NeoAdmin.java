package com.corinis.neo4jadmin.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.springframework.data.neo4j.conversion.Result;
import org.springframework.data.neo4j.support.Neo4jTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import com.corinis.neo4jadmin.model.Neo4jAdminAccessController;
import com.corinis.neo4jadmin.model.RPC;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

public class NeoAdmin extends HttpServlet {

	/** the jackson mapper to serialize the output */
	private final static ObjectMapper mapper;
	
	/**
	 * this allows fine graded access controlling of the admin.
	 */
	private Neo4jAdminAccessController accessController;
	
	/**
	 * automatically create new sessions
	 */
	private boolean autoCreateSession = true;
	
	static {
		// the jackson stuff
		mapper = new ObjectMapper().configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		mapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
		mapper.setAnnotationIntrospector(new JacksonAnnotationIntrospector());
	}

	private static final long serialVersionUID = 1L;
	
	protected static final String CHAR_ENCODING = "UTF-8";

	private Neo4jTemplate template;
	private HashMap<String, Session> sessionMap = new HashMap<String, NeoAdmin.Session>();
	
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		
		// get the root web application context to get the Neo4JTemplate 
		WebApplicationContext context = WebApplicationContextUtils.getRequiredWebApplicationContext(config.getServletContext());
		template = context.getBean(Neo4jTemplate.class);
		
		// check init parameter
		this.autoCreateSession = "true".equalsIgnoreCase(config.getInitParameter("autoCreateSession"));
		String accessControllerBean = config.getInitParameter("accessControllerBean");
		if(accessControllerBean != null) {
			if("auto".equalsIgnoreCase(accessControllerBean)) {
				accessController = context.getBean(Neo4jAdminAccessController.class);
			} else {
				accessController = context.getBean(accessControllerBean, Neo4jAdminAccessController.class);
			}
		}

	}

	public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}

	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		PrintWriter out = response.getWriter();

		
		HttpSession session = request.getSession(autoCreateSession);
		if(accessController != null) {
			accessController.canAccess(session);
		} else {
			// no access controller specified - just check if we have any session for mapping
			if(session == null) {
				response.setStatus(HttpStatus.FORBIDDEN.value());
				out.write("Please Login or create a session first");
				return;
			}
		}
		 
		setExpiration(response, null, true);
		response.setContentType("application/json;charset=" + CHAR_ENCODING);

		RPC rpc = null;
		try {
			rpc = (RPC) mapper.readValue(request.getReader(), RPC.class);
		} catch (Exception e) {
			writeStatus(out, null, "problem understanding request " + e.getMessage(), rpc.id);
			return;
		}
		
		
		// get the session
		Session cur = sessionMap.get(session.getId());
		if(cur == null) {
			cur = new Session();
			cur.curNode = 1;
			sessionMap.put(session.getId(), cur);
		}

		// if available check if we are allowed to do the given rpc call
		if(accessController != null && !accessController.canExecute(session, rpc, cur.curNode)) {
			writeStatus(out, null, "Execution denied. You do not have sufficien rights!", rpc.id);
			return;
		}

		// custom browse through nodes
		long curNode = cur.curNode;
		if("exit".equals(rpc.method)) {
			sessionMap.remove(session.getId());
			writeStatus(out, "logged out.", null, rpc.id);
			return;
		}
		if("help".equals(rpc.method)) {
			writeStatus(out, "Usage:\\n======\\n\\ncd /nodeId/\\n"+
					"\\tchange to node (you can use .. to go one node back)\\n" + 
					"ll\\n\\tshow node info (links only)\\n"+
					"la\\n\\tshow node info (attributes only)\\n"+
					"ls\\n\\tshow node info\\n"+
					"pwd\\n\\tshow current path/working node\\n" +
					"set [-f] property value\\n\\tset a property. use \"value\" for strings\\n" + 
					"start\\n\\tstart of a cyper query\\n", null, rpc.id);
			return;
		}
		if("pwd".equals(rpc.method)) {
			StringBuffer sb = new StringBuffer();
			for(long cid : cur.history) {
				sb.append(cid);
				sb.append('/');
			}
			sb.append(cur.curNode);
			sb.append("\\n");
			writeStatus(out, sb.toString(), null, rpc.id);
			return;
		}

		if("set".equals(rpc.method)) {
			Node n = template.getInfrastructure().getGraphDatabaseService().getNodeById(curNode);
			if(rpc.params.length < 2) {
				writeStatus(out, null, "Usage: \\tset property value", rpc.id);
				return;
			}
			boolean force = false;
			String field = rpc.params[0];
			String value = rpc.params[1];
			if("-f".equalsIgnoreCase(rpc.params[0])) {
				if (rpc.params.length < 3){
					writeStatus(out, null, "Usage: \\n\\tset -f property value", rpc.id);
					return;
				}
				force = true;
				field = rpc.params[1];
				value = rpc.params[2];
			}
			
			// check if the property exists
			if(!force) {
				try {
					if(!n.hasProperty(field)) {
						writeStatus(out, null, "Trying to set unexistant property! Use set -f to force!", rpc.id);
						return;
					}
				} catch (Exception e) {
					writeStatus(out, null, "Trying to set unexistant property" + field + "! Use set -f to force! (" +e.getMessage() + ")", rpc.id);
					return;
				}
			}
			Transaction tx = null;
			try {
				tx = template.getInfrastructure().getGraphDatabaseService().beginTx();
				// cut away quotes
				if(value.startsWith("\"") || value.startsWith("'")) {
					value = value.substring(1, value.length() - 1);
					n.setProperty(field, value);
				} 
				else if(value.equalsIgnoreCase("true"))
						n.setProperty(field, true);
				else if(value.equalsIgnoreCase("false"))
					n.setProperty(field, false);
				else if(value.indexOf('.') != -1) {
					// looks like a long/int
					n.setProperty(field, new Double(value));
				} else
					n.setProperty(field, new Long(value));
				tx.success();
			} 
			catch (Exception e) {
				
				writeStatus(out, null, "Unable to update " + curNode + "/" + field + ": " + e.getMessage(), rpc.id);
				return;
			} finally {
				if(tx != null) {
					tx.finish();
				}
			}
			
			writeStatus(out, "updated " + curNode + ": " + field + " = " + value, null, rpc.id);
			return;
		}

		if("find".equals(rpc.method)) {
			// String field = rpc.params[0];
			// String value = rpc.params[1];
		}
		if("cd".equals(rpc.method)) {
			if("..".equals(rpc.params[0])) {
				curNode = cur.history.get(cur.history.size()-1);
				cur.curNode = curNode;
				cur.history.remove(cur.history.size()-1);
				writeStatus(out, "changed node to " + cur.curNode, null, rpc.id);
				return;
			}
			try {
				long newId = Long.parseLong(rpc.params[0]);
				curNode = newId;
				cur.history.add(cur.curNode);
				// update node
				cur.curNode = curNode;
				writeStatus(out, "changed node to " + cur.curNode, null, rpc.id);
			}catch (NumberFormatException e) {
				writeStatus(out, null, "Unable to find node " + rpc.params[0], rpc.id);
			}
			return;
		}
		if("start".equalsIgnoreCase(rpc.method)) {
			StringBuilder sb = new StringBuilder();
			sb.append("START");
			for(String s : rpc.params) {
				sb.append(' ');
				sb.append(s);
			}
			Result<Map<String, Object>> ret = template.query(sb.toString(), null);
			for(Map<String, Object> res : ret) {
				sb = new StringBuilder();
				
				for(Entry<?, ?> e : res.entrySet()) {
					sb.append(e.getKey());
					sb.append(" = ");
					sb.append(e.getValue());
					sb.append("\\n");
				}
			}
		}
		if("ls".equals(rpc.method)) {
			Node n = template.getNode(curNode);
			writeStatus(out, nodeToString(n, true, true), null, rpc.id);
			return;
		} else	if("la".equals(rpc.method)) {
			Node n = template.getNode(curNode);
			writeStatus(out, nodeToString(n, true, false), null, rpc.id);
			return;
		} else if("ll".equals(rpc.method)) {
			Node n = template.getNode(curNode);
			writeStatus(out, nodeToString(n, false, true), null, rpc.id);
			return;
		}
		
		writeStatus(out, null, "unknown command", rpc.id);
	}

	private String nodeToString(Node n, boolean showatts, boolean showlinks) {
		StringBuilder msg = new StringBuilder();
		if(showatts) {
			for(String s : n.getPropertyKeys()) {
				msg.append(" *");
				msg.append(s);
				msg.append(" = ");
				msg.append(String.valueOf(n.getProperty(s)));
				msg.append("\\n");
			}
		}
		if(showlinks) {
			for(Relationship rel : n.getRelationships(Direction.OUTGOING)) {
				msg.append(" (me)-[:");
				msg.append(rel.getType().name());
				for(String rkey : rel.getPropertyKeys()) {
					if(rkey.equals("__type__"))
						continue;
					msg.append("," + rkey + ":" + rel.getProperty(rkey));
				}
				msg.append("]-&gt;(");
				Node o = rel.getOtherNode(n);
				if(o.hasProperty("name")) 
					msg.append(o.getProperty("name")+ ",");
				if(o.hasProperty("type"))
					msg.append("type:" + o.getProperty("type") + ",");
				msg.append(String.valueOf(o.getId()));
				msg.append(")\\n");
			}
			for(Relationship rel : n.getRelationships(Direction.INCOMING)) {
				msg.append(" (me)&lt;-[:");
				msg.append(rel.getType().name());
				for(String rkey : rel.getPropertyKeys()) {
					if(rkey.equals("__type__"))
						continue;
					msg.append("," + rkey + ":" + rel.getProperty(rkey));
				}
				msg.append("]-(");
				Node o = rel.getOtherNode(n);
				if(o.hasProperty("name")) 
					msg.append(o.getProperty("name")+ ",");
				if(o.hasProperty("type"))
					msg.append("type:" + o.getProperty("type") + ",");
				msg.append(String.valueOf(o.getId()));
				msg.append(")\\n");
			}
		}
		return msg.toString();
	}
	
	class Session {
		long curNode;
		ArrayList<Long> history = new ArrayList<Long>();
	}
	
	protected void writeStatus(PrintWriter out, String message, String error, long id) {
		out.println("{");

		out.print("\"result\": ");
		if(message != null) {
			out.print('"');
			out.print(message.replace('"', '\''));
			out.print('"');
		} else
			out.print("null");
		out.print(", \"error\": ");

		if(error != null) {
			out.print("{ \"code\": -1, \"message\": \"");
			out.print(error.replace('"', '\''));
			out.print("\"} ");
		} else out.print("null");
		out.println(", \"id\":"+id+"}\n");
		out.flush();
	}
	
	/**
	 * Set expiration-date.
	 * Must be called before any data will be sent to the response (out).
	 * <p>
	 * <u>Hint:</u> setExpiration(response, dExpires, false) should be
	 * called if the content-type is &quot;<code>application/pdf</code>&quot;,
	 * otherwise Internet Explorer 6.0 or the Acrobat pdf-plugin sometimes
	 * will not accept the document. Valid date-formats see:
	 * {@link "http://www.w3.org/Protocols/rfc2616/rfc2616-sec3.html#sec3.3.1"}
	 *
	 * @param response http-servlet-response
	 * @param dtExpiration expiration date/time
	 * @param bCacheControl will set &quot:Cache-Control&quot; in http-header
	 *                      if set to <code>true</code>
	 */

	private void setExpiration(HttpServletResponse response, Date dtExpiration, boolean bCacheControl) {
		StringBuffer sbExpiration = new StringBuffer(); // expiration-string in http-header
		Calendar calExpiration = null; // calendar for expiration-date


		calExpiration = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.UK); // get current GMT date/time

		if(dtExpiration == null) {
			calExpiration.set(2000, 0, 1, 0, 0, 0); // 2000/01/01 00:00 (millenium)
		} else {
			calExpiration.setTime(dtExpiration); // date/time from param
		}

		SimpleDateFormat sdfDateTime = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US);
		sdfDateTime.format(calExpiration.getTime(), sbExpiration, new FieldPosition(DateFormat.DAY_OF_WEEK_FIELD));

		if(sbExpiration != null) {
			sbExpiration.append(" GMT"); // fix to GMT time-zone

			response.setHeader("Expires", sbExpiration.toString()); // set expiration
		}

		// do not allow proxies to cache, if expiration is in the past
		if(System.currentTimeMillis() > calExpiration.getTimeInMillis()) {
			response.setHeader("Pragma", "no-cache"); // for HTTP/1.0

			if(bCacheControl) {
				response.setHeader("Cache-Control", "no-cache,max-age=0,must-revalidate"); //for HTTP/1.1
			}
		}
	}
}
