package com.example.voltahome.itsme;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * README: "Hand-over". Boundary with the application's token management (out of scope of the sample).
 * The implementation issues the application's own token/session and writes the response.
 * Reminder: the callback is a browser navigation, not an XHR call.
 */
public interface LoginHandover {

    void complete(HttpServletRequest request, HttpServletResponse response, ItsmeLoginResult result) throws IOException;
}
