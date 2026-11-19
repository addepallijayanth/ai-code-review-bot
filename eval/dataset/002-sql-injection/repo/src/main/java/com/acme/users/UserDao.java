package com.acme.users;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

public class UserDao {

    private final DataSource dataSource;

    public UserDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<User> findById(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id, name, email FROM users WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        }
    }

    public List<User> searchByName(String name) throws SQLException {
        String sql = "SELECT id, name, email FROM users WHERE name LIKE '%" + name + "%' ORDER BY name";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<User> users = new ArrayList<>();
            while (rows.next()) {
                users.add(map(rows));
            }
            return users;
        }
    }

    private static User map(ResultSet rows) throws SQLException {
        return new User(rows.getLong("id"), rows.getString("name"), rows.getString("email"));
    }
}
