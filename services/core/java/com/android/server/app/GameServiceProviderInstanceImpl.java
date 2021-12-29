/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.games.CreateGameSessionRequest;
import android.service.games.CreateGameSessionResult;
import android.service.games.GameScreenshotResult;
import android.service.games.GameSessionViewHostConfiguration;
import android.service.games.GameStartedEvent;
import android.service.games.IGameService;
import android.service.games.IGameServiceController;
import android.service.games.IGameSession;
import android.service.games.IGameSessionController;
import android.service.games.IGameSessionService;
import android.util.Slog;
import android.view.SurfaceControlViewHost.SurfacePackage;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerService;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

final class GameServiceProviderInstanceImpl implements GameServiceProviderInstance {
    private static final String TAG = "GameServiceProviderInstance";
    private static final int CREATE_GAME_SESSION_TIMEOUT_MS = 10_000;
    private static final boolean DEBUG = false;

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
            if (componentName == null) {
                return;
            }

            mBackgroundExecutor.execute(() -> {
                GameServiceProviderInstanceImpl.this.onTaskCreated(taskId, componentName);
            });
        }

        @Override
        public void onTaskRemoved(int taskId) throws RemoteException {
            mBackgroundExecutor.execute(() -> {
                GameServiceProviderInstanceImpl.this.onTaskRemoved(taskId);
            });
        }

        // TODO(b/204503192): Limit the lifespan of the game session in the Game Service provider
        // to only when the associated task is running. Right now it is possible for a task to
        // move into the background and for all associated processes to die and for the Game Session
        // provider's GameSessionService to continue to be running. Ideally we could unbind the
        // service when this happens.
    };

    private final IGameServiceController mGameServiceController =
            new IGameServiceController.Stub() {
                @Override
                public void createGameSession(int taskId) {
                    mBackgroundExecutor.execute(() -> {
                        GameServiceProviderInstanceImpl.this.createGameSession(taskId);
                    });
                }
            };

    private final IGameSessionController mGameSessionController =
            new IGameSessionController.Stub() {
                @Override
                public void takeScreenshot(int taskId,
                        @NonNull AndroidFuture gameScreenshotResultFuture) {
                    mBackgroundExecutor.execute(() -> {
                        GameServiceProviderInstanceImpl.this.takeScreenshot(taskId,
                                gameScreenshotResultFuture);
                    });
                }
            };

    private final Object mLock = new Object();
    private final UserHandle mUserHandle;
    private final Executor mBackgroundExecutor;
    private final GameClassifier mGameClassifier;
    private final IActivityTaskManager mActivityTaskManager;
    private final WindowManagerService mWindowManagerService;
    private final WindowManagerInternal mWindowManagerInternal;
    private final ServiceConnector<IGameService> mGameServiceConnector;
    private final ServiceConnector<IGameSessionService> mGameSessionServiceConnector;

    @GuardedBy("mLock")
    private final ConcurrentHashMap<Integer, GameSessionRecord> mGameSessions =
            new ConcurrentHashMap<>();
    @GuardedBy("mLock")
    private volatile boolean mIsRunning;

    GameServiceProviderInstanceImpl(
            @NonNull UserHandle userHandle,
            @NonNull Executor backgroundExecutor,
            @NonNull GameClassifier gameClassifier,
            @NonNull IActivityTaskManager activityTaskManager,
            @NonNull WindowManagerService windowManagerService,
            @NonNull WindowManagerInternal windowManagerInternal,
            @NonNull ServiceConnector<IGameService> gameServiceConnector,
            @NonNull ServiceConnector<IGameSessionService> gameSessionServiceConnector) {
        mUserHandle = userHandle;
        mBackgroundExecutor = backgroundExecutor;
        mGameClassifier = gameClassifier;
        mActivityTaskManager = activityTaskManager;
        mWindowManagerService = windowManagerService;
        mWindowManagerInternal = windowManagerInternal;
        mGameServiceConnector = gameServiceConnector;
        mGameSessionServiceConnector = gameSessionServiceConnector;
    }

    @Override
    public void start() {
        synchronized (mLock) {
            startLocked();
        }
    }

    @Override
    public void stop() {
        synchronized (mLock) {
            stopLocked();
        }
    }

    @GuardedBy("mLock")
    private void startLocked() {
        if (mIsRunning) {
            return;
        }
        mIsRunning = true;

        // TODO(b/204503192): In cases where the connection to the game service fails retry with
        //  back off mechanism.
        AndroidFuture<Void> unusedPostConnectedFuture = mGameServiceConnector.post(gameService -> {
            gameService.connected(mGameServiceController);
        });

        try {
            mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to register task stack listener", e);
        }
    }

    @GuardedBy("mLock")
    private void stopLocked() {
        if (!mIsRunning) {
            return;
        }
        mIsRunning = false;

        try {
            mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to unregister task stack listener", e);
        }

        for (GameSessionRecord gameSessionRecord : mGameSessions.values()) {
            destroyGameSessionFromRecord(gameSessionRecord);
        }
        mGameSessions.clear();

        // TODO(b/204503192): It is possible that the game service is disconnected. In this
        //  case we should avoid rebinding just to shut it down again.
        AndroidFuture<Void> unusedPostDisconnectedFuture =
                mGameServiceConnector.post(gameService -> {
                    gameService.disconnected();
                });
        mGameServiceConnector.unbind();
        mGameSessionServiceConnector.unbind();
    }

    private void onTaskCreated(int taskId, @NonNull ComponentName componentName) {
        String packageName = componentName.getPackageName();
        if (!mGameClassifier.isGame(packageName, mUserHandle)) {
            return;
        }

        synchronized (mLock) {
            gameTaskStartedLocked(taskId, componentName);
        }
    }

    @GuardedBy("mLock")
    private void gameTaskStartedLocked(int taskId, @NonNull ComponentName componentName) {
        if (DEBUG) {
            Slog.i(TAG, "gameStartedLocked() id: " + taskId + " component: " + componentName);
        }

        if (!mIsRunning) {
            return;
        }

        GameSessionRecord existingGameSessionRecord = mGameSessions.get(taskId);
        if (existingGameSessionRecord != null) {
            Slog.w(TAG, "Existing game session found for task (id: " + taskId
                    + ") creation. Ignoring.");
            return;
        }

        GameSessionRecord gameSessionRecord = GameSessionRecord.awaitingGameSessionRequest(
                taskId, componentName);
        mGameSessions.put(taskId, gameSessionRecord);

        AndroidFuture<Void> unusedPostGameStartedFuture = mGameServiceConnector.post(
                gameService -> {
                    gameService.gameStarted(
                            new GameStartedEvent(taskId, componentName.getPackageName()));
                });
    }

    private void onTaskRemoved(int taskId) {
        synchronized (mLock) {
            boolean isTaskAssociatedWithGameSession = mGameSessions.containsKey(taskId);
            if (!isTaskAssociatedWithGameSession) {
                return;
            }

            removeAndDestroyGameSessionIfNecessaryLocked(taskId);
        }
    }

    private void createGameSession(int taskId) {
        synchronized (mLock) {
            createGameSessionLocked(taskId);
        }
    }

    @GuardedBy("mLock")
    private void createGameSessionLocked(int taskId) {
        if (DEBUG) {
            Slog.i(TAG, "createGameSessionLocked() id: " + taskId);
        }

        if (!mIsRunning) {
            return;
        }

        GameSessionRecord existingGameSessionRecord = mGameSessions.get(taskId);
        if (existingGameSessionRecord == null) {
            Slog.w(TAG, "No existing game session record found for task (id: " + taskId
                    + ") creation. Ignoring.");
            return;
        }
        if (!existingGameSessionRecord.isAwaitingGameSessionRequest()) {
            Slog.w(TAG, "Existing game session for task (id: " + taskId
                    + ") is not awaiting game session request. Ignoring.");
            return;
        }

        GameSessionViewHostConfiguration gameSessionViewHostConfiguration =
                createViewHostConfigurationForTask(taskId);
        if (gameSessionViewHostConfiguration == null) {
            Slog.w(TAG, "Failed to create view host configuration for task (id" + taskId
                    + ") creation. Ignoring.");
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "Determined initial view host configuration for task (id: " + taskId + "): "
                    + gameSessionViewHostConfiguration);
        }

        mGameSessions.put(taskId, existingGameSessionRecord.withGameSessionRequested());

        AndroidFuture<CreateGameSessionResult> createGameSessionResultFuture =
                new AndroidFuture<CreateGameSessionResult>()
                        .orTimeout(CREATE_GAME_SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .whenCompleteAsync((createGameSessionResult, exception) -> {
                            if (exception != null || createGameSessionResult == null) {
                                Slog.w(TAG, "Failed to create GameSession: "
                                                + existingGameSessionRecord,
                                        exception);
                                synchronized (mLock) {
                                    removeAndDestroyGameSessionIfNecessaryLocked(taskId);
                                }
                                return;
                            }

                            synchronized (mLock) {
                                attachGameSessionLocked(taskId, createGameSessionResult);
                            }
                        }, mBackgroundExecutor);

        AndroidFuture<Void> unusedPostCreateGameSessionFuture =
                mGameSessionServiceConnector.post(gameService -> {
                    CreateGameSessionRequest createGameSessionRequest =
                            new CreateGameSessionRequest(
                                    taskId,
                                    existingGameSessionRecord.getComponentName().getPackageName());
                    gameService.create(
                            mGameSessionController,
                            createGameSessionRequest,
                            gameSessionViewHostConfiguration,
                            createGameSessionResultFuture);
                });
    }

    @GuardedBy("mLock")
    private void attachGameSessionLocked(
            int taskId,
            @NonNull CreateGameSessionResult createGameSessionResult) {
        if (DEBUG) {
            Slog.d(TAG, "attachGameSession() id: " + taskId);
        }

        GameSessionRecord gameSessionRecord = mGameSessions.get(taskId);

        if (gameSessionRecord == null) {
            Slog.w(TAG, "No associated game session record. Destroying id: " + taskId);
            destroyGameSessionDuringAttach(taskId, createGameSessionResult);
            return;
        }

        if (!gameSessionRecord.isGameSessionRequested()) {
            destroyGameSessionDuringAttach(taskId, createGameSessionResult);
            return;
        }

        try {
            mWindowManagerInternal.addTaskOverlay(
                    taskId,
                    createGameSessionResult.getSurfacePackage());
        } catch (IllegalArgumentException ex) {
            Slog.w(TAG, "Failed to add task overlay. Destroying id: " + taskId);
            destroyGameSessionDuringAttach(taskId, createGameSessionResult);
            return;
        }

        mGameSessions.put(taskId,
                gameSessionRecord.withGameSession(
                        createGameSessionResult.getGameSession(),
                        createGameSessionResult.getSurfacePackage()));
    }

    private void destroyGameSessionDuringAttach(
            int taskId,
            CreateGameSessionResult createGameSessionResult) {
        try {
            createGameSessionResult.getGameSession().destroy();
        } catch (RemoteException ex) {
            Slog.w(TAG, "Failed to destroy session: " + taskId);
        }
    }

    @GuardedBy("mLock")
    private void removeAndDestroyGameSessionIfNecessaryLocked(int taskId) {
        if (DEBUG) {
            Slog.d(TAG, "destroyGameSession() id: " + taskId);
        }

        GameSessionRecord gameSessionRecord = mGameSessions.remove(taskId);
        if (gameSessionRecord == null) {
            if (DEBUG) {
                Slog.w(TAG, "No game session found for id: " + taskId);
            }
            return;
        }
        destroyGameSessionFromRecord(gameSessionRecord);
    }

    private void destroyGameSessionFromRecord(@NonNull GameSessionRecord gameSessionRecord) {
        SurfacePackage surfacePackage = gameSessionRecord.getSurfacePackage();
        if (surfacePackage != null) {
            try {
                mWindowManagerInternal.removeTaskOverlay(
                        gameSessionRecord.getTaskId(),
                        surfacePackage);
            } catch (IllegalArgumentException ex) {
                Slog.i(TAG,
                        "Failed to remove task overlay. This is expected if the task is already "
                                + "destroyed: "
                                + gameSessionRecord);
            }
        }

        IGameSession gameSession = gameSessionRecord.getGameSession();
        if (gameSession != null) {
            try {
                gameSession.destroy();
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to destroy session: " + gameSessionRecord, ex);
            }
        }

        if (mGameSessions.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "No active game sessions. Disconnecting GameSessionService");
            }

            if (mGameSessionServiceConnector != null) {
                mGameSessionServiceConnector.unbind();
            }
        }
    }

    @Nullable
    private GameSessionViewHostConfiguration createViewHostConfigurationForTask(int taskId) {
        RunningTaskInfo runningTaskInfo = getRunningTaskInfoForTask(taskId);
        if (runningTaskInfo == null) {
            return null;
        }

        Rect bounds = runningTaskInfo.configuration.windowConfiguration.getBounds();
        return new GameSessionViewHostConfiguration(
                runningTaskInfo.displayId,
                bounds.width(),
                bounds.height());
    }

    @Nullable
    private RunningTaskInfo getRunningTaskInfoForTask(int taskId) {
        List<RunningTaskInfo> runningTaskInfos;
        try {
            runningTaskInfos = mActivityTaskManager.getTasks(
                    /* maxNum= */ Integer.MAX_VALUE,
                    /* filterOnlyVisibleRecents= */ true,
                    /* keepIntentExtra= */ false);
        } catch (RemoteException ex) {
            Slog.w(TAG, "Failed to fetch running tasks");
            return null;
        }

        for (RunningTaskInfo taskInfo : runningTaskInfos) {
            if (taskInfo.taskId == taskId) {
                return taskInfo;
            }
        }

        return null;
    }

    @VisibleForTesting
    void takeScreenshot(int taskId, @NonNull AndroidFuture callback) {
        synchronized (mLock) {
            boolean isTaskAssociatedWithGameSession = mGameSessions.containsKey(taskId);
            if (!isTaskAssociatedWithGameSession) {
                Slog.w(TAG, "No game session found for id: " + taskId);
                callback.complete(GameScreenshotResult.createInternalErrorResult());
                return;
            }
        }

        mBackgroundExecutor.execute(() -> {
            final Bitmap bitmap = mWindowManagerService.captureTaskBitmap(taskId);
            if (bitmap == null) {
                Slog.w(TAG, "Could not get bitmap for id: " + taskId);
                callback.complete(GameScreenshotResult.createInternalErrorResult());
            } else {
                callback.complete(GameScreenshotResult.createSuccessResult(bitmap));
            }
        });
    }
}
