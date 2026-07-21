# GitHub 发布与公开 README Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Publish the current `m1-file-operations` branch and complete Git history to `oldbkroll/miwatchfiles-forxiaomiwatch5`, with a concise public-facing README and no PR creation.

**Architecture:** Keep the existing local branch and history unchanged. Replace the internal-session-oriented root README with a standalone project overview, then use an explicit Git commit and tracked remote branch push; leave the user memo untracked.

**Tech Stack:** Markdown, local Git, GitHub CLI 2.96.0, GitHub HTTPS remote.

## Global Constraints

- Preserve the current branch `m1-file-operations` and all existing Git history.
- Do not modify, stage, commit, delete, or upload the untracked memo.
- Do not create a worktree, reset the repository, or create a pull request.
- Keep the existing `targetSdk 29`, `requestLegacyExternalStorage`, and `armeabi-v7a` project constraints documented accurately.

---

### Task 1: Write the public README

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: the current M2-complete project state and existing build/install conventions.
- Produces: a GitHub-facing README that does not mention Superpowers workflows.

- [ ] **Step 1: Replace the internal-session opening with project overview**

Write the project purpose, current M0–M2 status, and primary feature list in Chinese. Do not add links or prose about `docs/superpowers`.

- [ ] **Step 2: Add compatibility and build/install sections**

Document Xiaomi Watch 5 / 480×480 / Android API 29 target / `armeabi-v7a`, Debug-only build commands, APK output path, package name, and ADB installation.

- [ ] **Step 3: Add limitations and testing boundaries**

Document that text editing is UTF-8-focused and sandboxed real-device write testing is limited to `M1Sandbox`; state that audio/video use other apps and advanced features remain future work.

- [ ] **Step 4: Review README for public scope**

Run `Select-String -Path README.md -Pattern 'superpowers|工作流|内部会话'` and confirm it produces no output. Review the full README for stale claims and secret-looking values.

### Task 2: Verify and commit the publishable scope

**Files:**
- Modify: `README.md`
- Create: `docs/superpowers/specs/2026-07-21-github-publish-readme-design.md`
- Create: `docs/superpowers/plans/2026-07-21-github-publish-readme.md`

**Interfaces:**
- Consumes: Task 1 README and the approved publish scope.
- Produces: one intentional commit that excludes the user memo.

- [ ] **Step 1: Inspect status and diff**

Run `git status -sb`, `git diff -- README.md`, and `git diff --check`. Confirm the memo remains the only untracked user file and is not part of the staged path list.

- [ ] **Step 2: Stage explicit paths only**

Run:

```powershell
git add -- README.md docs/superpowers/specs/2026-07-21-github-publish-readme-design.md docs/superpowers/plans/2026-07-21-github-publish-readme.md
git diff --cached --check
```

Expected: no whitespace errors and no memo in `git status --short` staged entries.

- [ ] **Step 3: Commit the README increment**

Run `git commit -m "docs: prepare GitHub project README"`. Expected: a new commit containing exactly the README and the approved internal design/plan records.

### Task 3: Configure the GitHub remote and push the existing branch

**Files:**
- Modify: local `.git/config` only through `git remote add`

**Interfaces:**
- Consumes: the committed current branch and authenticated `gh` session.
- Produces: remote `origin` pointing to `https://github.com/oldbkroll/miwatchfiles-forxiaomiwatch5.git` and an uploaded `m1-file-operations` branch.

- [ ] **Step 1: Verify the repository and authentication**

Run `gh repo view oldbkroll/miwatchfiles-forxiaomiwatch5 --json nameWithOwner,isPrivate,defaultBranchRef` and `gh auth status`. Expected: the named repository is accessible and the active account is `oldbkroll`.

- [ ] **Step 2: Add or verify origin**

If no `origin` exists, run `git remote add origin https://github.com/oldbkroll/miwatchfiles-forxiaomiwatch5.git`; otherwise verify the existing URL matches exactly. Do not replace an unrelated remote silently.

- [ ] **Step 3: Push only the current branch**

Run `git push -u origin m1-file-operations`. Expected: the complete existing branch history and the new README commit are uploaded; no PR is created.

- [ ] **Step 4: Verify the remote branch and local memo**

Run `git ls-remote --heads origin m1-file-operations`, `git status --short`, and `git log --oneline -3`. Expected: the remote branch resolves to the local HEAD, and the memo remains untracked and unstaged.
