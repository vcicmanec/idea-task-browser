package org.github.olloginov.ideataskbrowser.tasks;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.tasks.TaskManager;
import com.intellij.tasks.TaskRepository;
import org.github.olloginov.ideataskbrowser.TaskBrowserBundle;
import org.github.olloginov.ideataskbrowser.TaskBrowserNotifier;
import org.github.olloginov.ideataskbrowser.exceptions.RepositoryException;
import org.github.olloginov.ideataskbrowser.model.TaskSearch;
import org.github.olloginov.ideataskbrowser.view.TaskSearchTreeNode;
import org.github.olloginov.ideataskbrowser.view.TaskTreeNode;
import org.github.olloginov.ideataskbrowser.view.TreeNodeRef;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.lang.reflect.InvocationTargetException;

public class FetchNewIssuesFromRepoTask extends Task.Backgroundable {
    private static final Logger logger = Logger.getInstance(FetchNewIssuesFromRepoTask.class);

    public static final int FETCH_ISSUES_BUFFER_SIZE = 1024;

    private final TreeNodeRef<TaskSearchTreeNode> searchNode;
    private final TaskBrowserNotifier notifier;

    public FetchNewIssuesFromRepoTask(@NotNull Project project, @NotNull TreeNodeRef<TaskSearchTreeNode> searchNode) {
        super(project, TaskBrowserBundle.message("FetchNewIssuesFromRepoTask.title", searchNode.getNode().getSearch().getRepository()), true);
        this.searchNode = searchNode;
        this.notifier = ServiceManager.getService(myProject, TaskBrowserNotifier.class);
    }


    public TaskSearchTreeNode getNode() {
        return searchNode.getNode();
    }

    public TaskSearch getSearch() {
        return getNode().getSearch();
    }

    private static class FetchContext {
        public ProgressIndicator indicator;
        public TaskRepository repository;

        public int addedCount;
        public int updatedCount;

        public boolean isAlive() {
            return repository != null;
        }
    }

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
        TaskSearch search = getSearch();

        TaskRepository repository = null;
        TaskManager manager = TaskManager.getManager(myProject);
        for (TaskRepository taskRepository : manager.getAllRepositories()) {
            if (search.getRepository().equals(taskRepository.getPresentableName())) {
                repository = taskRepository;
                break;
            }
        }

        if (repository == null || indicator.isCanceled()) {
            return;
        }

        FetchContext ctx = new FetchContext();
        ctx.indicator = indicator;
        ctx.repository = repository;
        // validate context
        if (!ctx.isAlive()) {
            return;
        }

        // don't run update twice
        if (!search.getUpdating().compareAndSet(false, true)) {
            return;
        }

        try {
            importNew(ctx);
            updateCurrent(ctx);
        } finally {
            search.getUpdating().set(false);
        }
    }

    public void importNew(FetchContext ctx) {
        String title = TaskBrowserBundle.message("task.FetchNewIssuesTask.title", ctx.repository.getPresentableName());
        try {
            fetchAll(ctx);
            if (ctx.addedCount > 0 && ctx.updatedCount > 0) {
                if (ctx.addedCount == ctx.updatedCount) {
                    notifier.info(title, TaskBrowserBundle.message("task.FetchNewIssuesTask.finishing.added", ctx.addedCount, pluralize("task", ctx.addedCount)));
                } else {
                    notifier.info(title, TaskBrowserBundle.message("task.FetchNewIssuesTask.finishing.addedAndUpdated", ctx.addedCount, pluralize("task", ctx.addedCount), ctx.updatedCount));
                }
            } else if (ctx.addedCount > 0) {
                notifier.info(title, TaskBrowserBundle.message("task.FetchNewIssuesTask.finishing.added", ctx.addedCount, pluralize("task", ctx.addedCount)));
            } else if (ctx.updatedCount > 0) {
                notifier.info(title, TaskBrowserBundle.message("task.FetchNewIssuesTask.finishing.updated", ctx.updatedCount, pluralize("task", ctx.updatedCount)));
            } else {
                notifier.info(title, TaskBrowserBundle.message("task.FetchNewIssuesTask.finishing.noIssues"));
            }
        } catch (Exception e) {
            notifier.error(title, TaskBrowserBundle.message("task.FetchNewIssuesTask.fetchError", e.getMessage()));
            if (!(e instanceof RepositoryException)) {
                logger.error(e);
            }
        }
    }

    private String pluralize(String messageBase, int count) {
        return TaskBrowserBundle.message(messageBase + (count > 1 ? ".many" : ".1"));
    }

    private void fetchAll(final FetchContext ctx) throws RepositoryException, InvocationTargetException, InterruptedException {
        String name = ctx.repository.getPresentableName();
        ctx.indicator.setText(TaskBrowserBundle.message("task.FetchNewIssuesTask.starting", name));

        String fetchQuery = getNode().getSearch().getQuery();

        com.intellij.tasks.Task[] tasks = fetchChanges(ctx, 0, FETCH_ISSUES_BUFFER_SIZE, fetchQuery);
        if (tasks == null || tasks.length <= 0) {
            return;
        }

        for (final com.intellij.tasks.Task task : tasks) {
            int taskNodeIndex = getNode().findTaskNode(task);

            if (taskNodeIndex < 0) {
                if(task.getState() == null && task.isClosed()){
                    continue;
                }

                ctx.addedCount++;

                // node not found, but got place where insert
                final int insertAt = -(taskNodeIndex + 1);
                SwingUtilities.invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        searchNode.insertChild(insertAt, new TaskTreeNode(task));
                    }
                });
            } else {
                ctx.updatedCount++;
            }
        }
    }

    public com.intellij.tasks.Task[] fetchChanges(FetchContext ctx, int offset, int limit, String fetchQuery) throws RepositoryException {
        try {
            return ctx.repository.getIssues(fetchQuery, offset, limit, false, ctx.indicator);
        } catch (Exception e) {
            throw new RepositoryException(TaskBrowserBundle.message("error.connection.broken") + ": " + e.getMessage(), e);
        }
    }

    public com.intellij.tasks.Task updateTask(FetchContext ctx, com.intellij.tasks.Task task) {
        try {
            return ctx.repository.findTask(task.getId());
        } catch (Exception e) {
            return null;
        }
    }

    private void updateCurrent(FetchContext ctx) {
        for (int index = 0, length = getNode().getChildCount(); index < length; ++index) {
            ctx.indicator.setFraction(index / (float) length);

            TaskTreeNode taskNode = getNode().getChildAt(index);
            com.intellij.tasks.Task task = updateTask(ctx, taskNode.getTask());
            if (task == null) {
                continue;
            }
            taskNode.setTask(task);
            searchNode.updateChild(taskNode);
        }
    }
}
