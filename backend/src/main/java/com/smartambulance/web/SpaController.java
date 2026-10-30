package com.smartambulance.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Lets the React router handle page URLs (e.g. a refresh on /police) when the web app is served by this backend. */
@Controller
public class SpaController {
    @GetMapping({"/login", "/register", "/patient", "/hospital", "/ambulance", "/police", "/driver", "/admin"})
    public String spa() {
        return "forward:/index.html";
    }
}
