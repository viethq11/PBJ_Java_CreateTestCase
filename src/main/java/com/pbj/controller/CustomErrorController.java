package com.pbj.controller;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    @org.springframework.web.bind.annotation.ResponseBody
    public String handleError() {
        return "<h1>Something went wrong</h1><p>Check the server logs for more details.</p><a href='/'>Try going back to home</a>";
    }
}
