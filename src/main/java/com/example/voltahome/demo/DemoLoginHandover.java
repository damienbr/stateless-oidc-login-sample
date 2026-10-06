package com.example.voltahome.demo;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.voltahome.itsme.ItsmeLoginResult;
import com.example.voltahome.itsme.LoginHandover;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * DEMO ONLY. Stands in for the application's token management, which is out of scope.
 * A real implementation issues the application's own token or session and redirects to the portal.
 * Here, the login result is simply written as JSON so that it can be inspected.
 */
@Component
class DemoLoginHandover implements LoginHandover {

    private final ObjectMapper json;

    DemoLoginHandover(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void complete(HttpServletRequest request, HttpServletResponse response, ItsmeLoginResult result)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", result.customerId());
        body.put("authenticationMethod", result.authenticationMethod());
        body.put("authenticationLevel", result.authenticationLevel());
        body.put("authenticationTime", result.authenticationTime().toString());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }
}
