package com.acme.admin;

import com.acme.users.User;
import com.acme.users.UserDao;
import java.sql.SQLException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminUserController {

    private final UserDao users;

    public AdminUserController(UserDao users) {
        this.users = users;
    }

    @GetMapping("/admin/users")
    public List<User> search(@RequestParam("q") String query) throws SQLException {
        return users.searchByName(query);
    }
}
