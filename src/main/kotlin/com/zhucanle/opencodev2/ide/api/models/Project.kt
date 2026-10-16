package com.zhucanle.opencodev2.ide.api.models

data class Project(
    val id: String,
    val worktree: String,
    val vcsDir: String? = null,
    val vcs: String? = null
)
