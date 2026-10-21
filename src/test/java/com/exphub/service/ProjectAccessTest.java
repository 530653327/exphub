package com.exphub.service;

import com.exphub.entity.Project;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目可见性判定：保证「有 Key 时必须归属一致、孤儿项目对任何 Key 不可见」。
 */
class ProjectAccessTest {

    @Test
    void backendWithoutKeyCanSeeAnyProject() {
        assertTrue(ProjectService.canAccess(project("key-a"), null));
        assertTrue(ProjectService.canAccess(project(null), null));
    }

    @Test
    void matchingKeyCanSeeProject() {
        assertTrue(ProjectService.canAccess(project("key-a"), "key-a"));
    }

    @Test
    void otherKeyCannotSeeProject() {
        assertFalse(ProjectService.canAccess(project("key-a"), "key-b"));
    }

    @Test
    void orphanProjectIsInvisibleToAnyKey() {
        assertFalse(ProjectService.canAccess(project(null), "key-a"));
        assertFalse(ProjectService.canAccess(project(null), "key-b"));
    }

    @Test
    void nullProjectIsInvisible() {
        assertFalse(ProjectService.canAccess(null, "key-a"));
        assertFalse(ProjectService.canAccess(null, null));
    }

    private static Project project(String apiKey) {
        Project p = new Project();
        p.setApiKey(apiKey);
        return p;
    }
}
