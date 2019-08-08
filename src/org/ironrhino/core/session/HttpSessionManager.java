package org.ironrhino.core.session;

import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

public interface HttpSessionManager extends HttpSessionStore {

	String REQUEST_ATTRIBUTE_KEY_SESSION_ID_FOR_API = "_session_id_in_request_for_api";

	String REQUEST_ATTRIBUTE_KEY_SESSION_MAP_FOR_API = "_session_map_in_request_for_api";

	String REQUEST_ATTRIBUTE_SESSION_MARK_AS_DIRTY = "_session_mark_as_dirty";

	String DEFAULT_SESSION_TRACKER_NAME = "T";

	String DEFAULT_SESSION_COOKIE_NAME = "s";

	String DEFAULT_COOKIE_NAME_LOCALE = "locale";

	int DEFAULT_LIFETIME = -1; // in seconds

	int DEFAULT_MAXINACTIVEINTERVAL = 1800; // in seconds

	int DEFAULT_MINACTIVEINTERVAL = 60;// in seconds

	String getSessionId(HttpServletRequest request);

	String changeSessionId(WrappedHttpSession session);

	String getSessionTrackerName();

	String getLocaleCookieName();

	Locale getLocale(HttpServletRequest request);

}
