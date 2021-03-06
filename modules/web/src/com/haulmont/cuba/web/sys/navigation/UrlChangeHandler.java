/*
 * Copyright (c) 2008-2018 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.haulmont.cuba.web.sys.navigation;

import com.haulmont.cuba.core.global.BeanLocator;
import com.haulmont.cuba.core.global.Configuration;
import com.haulmont.cuba.core.global.Messages;
import com.haulmont.cuba.gui.Notifications.NotificationType;
import com.haulmont.cuba.gui.Screens;
import com.haulmont.cuba.gui.components.CloseOriginType;
import com.haulmont.cuba.gui.components.Window;
import com.haulmont.cuba.gui.config.WindowConfig;
import com.haulmont.cuba.gui.navigation.NavigationState;
import com.haulmont.cuba.gui.screen.FrameOwner;
import com.haulmont.cuba.gui.screen.Screen;
import com.haulmont.cuba.gui.screen.UiControllerUtils;
import com.haulmont.cuba.gui.util.OperationResult;
import com.haulmont.cuba.web.App;
import com.haulmont.cuba.web.AppUI;
import com.haulmont.cuba.web.WebConfig;
import com.haulmont.cuba.web.controllers.ControllerUtils;
import com.haulmont.cuba.web.gui.UrlHandlingMode;
import com.haulmont.cuba.web.gui.WebWindow;
import com.haulmont.cuba.web.sys.RedirectHandler;
import com.haulmont.cuba.web.sys.navigation.accessfilter.NavigationFilter;
import com.haulmont.cuba.web.sys.navigation.accessfilter.NavigationFilter.AccessCheckResult;
import com.vaadin.server.Page;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static com.haulmont.cuba.web.sys.navigation.UrlTools.pushState;

public class UrlChangeHandler implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(UrlChangeHandler.class);

    @Inject
    protected Messages messages;
    @Inject
    protected WebConfig webConfig;
    @Inject
    protected WindowConfig windowConfig;
    @Inject
    protected List<NavigationFilter> accessFilters;
    @Inject
    protected BeanLocator beanLocator;

    protected AppUI ui;

    protected HistoryNavigator historyNavigator;
    protected ScreenNavigator screenNavigator;

    public UrlChangeHandler(AppUI ui) {
        this.ui = ui;
    }

    @Override
    public void afterPropertiesSet() {
        historyNavigator = new HistoryNavigator(this, ui);
        screenNavigator = beanLocator.getPrototype(ScreenNavigator.NAME, this, ui);
    }

    public void handleUrlChange(Page.PopStateEvent event) {
        if (notSuitableMode()) {
            log.debug("UrlChangeHandler is disabled for '{}' URL handling mode", webConfig.getUrlHandlingMode());
            return;
        }

        int hashIdx = event.getUri().indexOf("#");
        NavigationState requestedState = hashIdx < 0
                ? NavigationState.EMPTY
                : UrlTools.parseState(event.getUri().substring(hashIdx + 1));

        if (requestedState == null) {
            log.debug("Unable to handle requested state: '{}'", Page.getCurrent().getUriFragment());
            reloadApp();
            return;
        }

        if (!App.getInstance().getConnection().isAuthenticated()) {
            handleNoAuthNavigation(requestedState);
            return;
        }

        __handleUrlChange(requestedState);
    }

    public ScreenNavigator getScreenNavigator() {
        return screenNavigator;
    }

    protected void __handleUrlChange(NavigationState requestedState) {
        boolean historyNavHandled = historyNavigator.handleHistoryNavigation(requestedState);
        if (!historyNavHandled) {
            screenNavigator.handleScreenNavigation(requestedState);
        }
    }

    @Nullable
    protected Screen getActiveScreen() {
        Iterator<Screen> dialogsIterator = getOpenedScreens().getDialogScreens().iterator();
        if (dialogsIterator.hasNext()) {
            return dialogsIterator.next();
        }

        Iterator<Screen> screensIterator = getOpenedScreens().getCurrentBreadcrumbs().iterator();
        if (screensIterator.hasNext()) {
            return screensIterator.next();
        }

        return getOpenedScreens().getRootScreenOrNull();
    }

    @Nullable
    protected Screen findScreenByState(Collection<Screen> screens, NavigationState requestedState) {
        return screens.stream()
                .filter(s -> Objects.equals(requestedState.getStateMark(), getStateMark(s)))
                .findFirst()
                .orElse(null);
    }

    protected void handleNoAuthNavigation(NavigationState requestedState) {
        if (Objects.equals(ui.getHistory().getNow(), requestedState)) {
            return;
        }

        String nestedRoute = requestedState.getNestedRoute();
        if (StringUtils.isNotEmpty(nestedRoute)) {
            RedirectHandler redirectHandler = beanLocator.getPrototype(RedirectHandler.NAME, ui);
            redirectHandler.schedule(requestedState);
            App.getInstance().setRedirectHandler(redirectHandler);

        } else if (isRootState(requestedState)) {
            Screen rootScreen = getOpenedScreens().getRootScreenOrNull();
            if (rootScreen != null) {
                pushState(getResolvedState(rootScreen).asRoute());
            }
        }

        showNotification(messages.getMainMessage("navigation.shouldLogInFirst"));
    }

    // Util methods

    protected void reloadApp() {
        String url = ControllerUtils.getLocationWithoutParams() + "?restartApp";
        ui.getPage().open(url, "_self");
    }

    protected boolean isRootState(NavigationState requestedState) {
        return StringUtils.isEmpty(requestedState.getStateMark())
                && StringUtils.isEmpty(requestedState.getNestedRoute());
    }

    protected boolean isCurrentRootState(NavigationState requestedState) {
        if (!isRootState(requestedState)) {
            return false;
        }

        Screen rootScreen = ui.getScreens().getOpenedScreens().getRootScreenOrNull();
        if (rootScreen == null) {
            return false;
        }

        String screenId = UiControllerUtils.getScreenContext(rootScreen)
                .getWindowInfo()
                .getId();

        String rootScreenRoute = windowConfig.findRoute(screenId);

        return Objects.equals(requestedState.getRoot(), rootScreenRoute);
    }

    protected String getStateMark(Screen screen) {
        WebWindow webWindow = (WebWindow) screen.getWindow();
        return String.valueOf(webWindow.getUrlStateMark());
    }

    protected Screen findActiveScreenByState(NavigationState requestedState) {
        Screen screen = findScreenByState(getOpenedScreens().getActiveScreens(), requestedState);

        if (screen == null && isCurrentRootState(requestedState)) {
            screen = ui.getScreens().getOpenedScreens().getRootScreenOrNull();
        }

        return screen;
    }

    protected Screen findScreenByState(NavigationState requestedState) {
        return findScreenByState(getOpenedScreens().getAll(), requestedState);
    }

    protected void selectScreen(Screen screen) {
        if (screen == null) {
            return;
        }

        for (Screens.WindowStack windowStack : getOpenedScreens().getWorkAreaStacks()) {
            Iterator<Screen> breadCrumbs = windowStack.getBreadcrumbs().iterator();
            if (breadCrumbs.hasNext()
                    && breadCrumbs.next() == screen) {

                windowStack.select();
                return;
            }
        }
    }

    protected void showNotification(String msg) {
        ui.getNotifications()
                .create(NotificationType.TRAY)
                .withCaption(msg)
                .show();
    }

    protected void revertNavigationState() {
        Screen screen = findActiveScreenByState(ui.getHistory().getNow());
        if (screen == null) {
            screen = getActiveScreen();
        }

        pushState(getResolvedState(screen).asRoute());
    }

    protected boolean notSuitableMode() {
        return UrlHandlingMode.URL_ROUTES != webConfig.getUrlHandlingMode();
    }

    protected NavigationState getResolvedState(Screen screen) {
        return screen != null
                ? ((WebWindow) screen.getWindow()).getResolvedState()
                : NavigationState.EMPTY;
    }

    protected AccessCheckResult navigationAllowed(NavigationState requestedState) {
        for (NavigationFilter filter : accessFilters) {
            AccessCheckResult result = filter.allowed(ui.getHistory().getNow(), requestedState);
            if (result.isRejected()) {
                return result;
            }
        }
        return AccessCheckResult.allowed();
    }

    protected Screens.OpenedScreens getOpenedScreens() {
        return ui.getScreens().getOpenedScreens();
    }

    // Copied from WebAppWorkArea

    protected boolean closeWindowStack(Screens.WindowStack windowStack) {
        boolean closed = true;

        for (Screen screen : windowStack.getBreadcrumbs()) {
            if (isNotCloseable(screen.getWindow())
                    || isWindowClosePrevented(screen.getWindow())) {
                closed = false;

                windowStack.select();

                break;
            }

            OperationResult closeResult = screen.close(FrameOwner.WINDOW_CLOSE_ACTION);
            if (closeResult.getStatus() != OperationResult.Status.SUCCESS) {
                closed = false;

                windowStack.select();

                break;
            }
        }
        return closed;
    }

    public boolean isNotCloseable(Window window) {
        if (!window.isCloseable()) {
            return true;
        }

        Configuration configuration = beanLocator.get(Configuration.NAME);
        WebConfig webConfig = configuration.getConfig(WebConfig.class);

        if (webConfig.getDefaultScreenCanBeClosed()) {
            return false;
        }

        boolean windowIsDefault;
        if (window instanceof Window.Wrapper) {
            windowIsDefault = ((WebWindow) ((Window.Wrapper) window).getWrappedWindow()).isDefaultScreenWindow();
        } else {
            windowIsDefault = ((WebWindow) window).isDefaultScreenWindow();
        }

        return windowIsDefault;
    }

    protected boolean isWindowClosePrevented(Window window) {
        Window.BeforeCloseEvent event = new Window.BeforeCloseEvent(window, CloseOriginType.CLOSE_BUTTON);

        ((WebWindow) window).fireBeforeClose(event);

        return event.isClosePrevented();
    }
}
