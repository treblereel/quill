package org.treblereel.mcp.fixture.spring;

public class UserDTO {

    private String name;
    private UserRepository userRepository;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UserRepository getUserRepository() {
        return userRepository;
    }
}
