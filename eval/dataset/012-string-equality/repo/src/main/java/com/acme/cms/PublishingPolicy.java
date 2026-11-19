package com.acme.cms;

public class PublishingPolicy {

    public boolean canPublish(User user, Article article) {
        if ("EDITOR" == user.getRole()) {
            return true;
        }
        return article.getAuthorId().equals(user.getId());
    }
}
