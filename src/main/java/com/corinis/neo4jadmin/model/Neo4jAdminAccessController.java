package com.corinis.neo4jadmin.model;

import javax.servlet.http.HttpSession;

public interface Neo4jAdminAccessController {
	/**
	 * Check if the given session can access the console.
	 * @param session the servlet session
	 * @return false to disallow any access
	 */
	public boolean canAccess(HttpSession session);

	/**
	 * @param session the servlet session
	 * @param rpc the rpc all to be checked
	 * @param curNode the current node id
	 * @return false to disallow execution of the given rpc call
	 */
	public boolean canExecute(HttpSession session, final RPC rpc, long curNode);
}
