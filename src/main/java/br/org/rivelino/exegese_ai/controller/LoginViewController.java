/*******************************************************************************
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
 * and associated documentation files (the "Software"), to deal in the Software without 
 * restriction, including without limitation the rights to use, copy, modify, merge, publish, 
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the 
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or 
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS 
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR 
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN 
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * This software uses third-party components, distributed accordingly to their own licenses.
 *******************************************************************************/
package br.org.rivelino.exegese_ai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Controller serving the institutional Google OAuth2 login page.
 * Maps the login failure codes ({@code /login?error=<code>}) and the deactivated-session redirect
 * ({@code /login?disabled}) to translated alert messages.
 *
 * @author Rivelino Patrício
 */
@Controller
public class LoginViewController {

    private static final String GENERIC_ERROR_KEY = "login.alert.error";

    private static final Map<String, String> ERROR_MESSAGE_KEYS = Map.of(
            "account_disabled", "login.alert.account_disabled",
            "email_not_verified", "login.alert.email_not_verified",
            "email_domain_not_allowed", "login.alert.email_domain_not_allowed"
    );

    @GetMapping("/login")
    public String login(@RequestParam(name = "error", required = false) String error,
                        @RequestParam(name = "disabled", required = false) String disabled,
                        Model model) {
        if (disabled != null) {
            model.addAttribute("loginErrorKey", "login.alert.account_disabled");
        } else if (error != null) {
            model.addAttribute("loginErrorKey", ERROR_MESSAGE_KEYS.getOrDefault(error, GENERIC_ERROR_KEY));
        }
        return "login";
    }
}
