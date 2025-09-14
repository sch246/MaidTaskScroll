package com.sch246.maidtaskscroll.event;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.sch246.maidtaskscroll.MaidTaskScroll;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = MaidTaskScroll.MODID, value = Dist.CLIENT)
public class ClientEventHandler {

    // 缓存反射结果以提高性能
    private static final ConcurrentHashMap<Class<?>, MaidGuiInfo> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final String MAID_GUI_CLASS_NAME = "AbstractMaidContainerGui";

    // 缓存类信息的内部类
    private static class MaidGuiInfo {
        final Class<?> maidGuiClass;
        final Field taskListOpenField;
        final Field taskPageField;
        final Field taskCountPerPageField;
        final Method initMethod;
        final boolean isValid;

        MaidGuiInfo(Class<?> screenClass) {
            this.maidGuiClass = findAbstractMaidContainerGuiClass(screenClass);
            if (maidGuiClass == null) {
                this.taskListOpenField = null;
                this.taskPageField = null;
                this.taskCountPerPageField = null;
                this.initMethod = null;
                this.isValid = false;
                return;
            }

            Field taskListOpen = null;
            Field taskPage = null;
            Field taskCountPerPage = null;
            Method init = null;

            try {
                taskListOpen = maidGuiClass.getDeclaredField("TASK_LIST_OPEN");
                taskListOpen.setAccessible(true);

                taskPage = maidGuiClass.getDeclaredField("TASK_PAGE");
                taskPage.setAccessible(true);

                taskCountPerPage = maidGuiClass.getDeclaredField("TASK_COUNT_PER_PAGE");
                taskCountPerPage.setAccessible(true);

                init = findInitMethod(screenClass);
                if (init != null) {
                    init.setAccessible(true);
                }
            } catch (NoSuchFieldException e) {
                MaidTaskScroll.LOGGER.warn("Failed to find required fields in {}", maidGuiClass.getName(), e);
            }

            this.taskListOpenField = taskListOpen;
            this.taskPageField = taskPage;
            this.taskCountPerPageField = taskCountPerPage;
            this.initMethod = init;
            this.isValid = taskListOpen != null && taskPage != null && taskCountPerPage != null;
        }
    }

    // 缓存 AbstractContainerScreen 字段
    private static Field leftPosField;
    private static Field topPosField;

    static {
        try {
            leftPosField = AbstractContainerScreen.class.getDeclaredField("leftPos");
            leftPosField.setAccessible(true);
            topPosField = AbstractContainerScreen.class.getDeclaredField("topPos");
            topPosField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            MaidTaskScroll.LOGGER.error("Failed to initialize container screen fields", e);
        }
    }

    @SubscribeEvent
    public static void onScreenMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        Screen screen = event.getScreen();

        // 快速检查是否为目标GUI类型
        if (!isAbstractMaidContainerGui(screen.getClass())) {
            return;
        }

        MaidGuiInfo guiInfo = getOrCreateGuiInfo(screen.getClass());
        if (!guiInfo.isValid) {
            return;
        }

        try {
            // 检查任务列表是否打开
            boolean taskListOpen = guiInfo.taskListOpenField.getBoolean(null);
            if (!taskListOpen) {
                return;
            }

            // 检查鼠标是否在任务列表区域
            if (!isMouseInTaskListArea(screen, event.getMouseX(), event.getMouseY())) {
                return;
            }

            handleTaskListScroll(screen, guiInfo, event.getScrollDeltaY(), event);

        } catch (Exception e) {
            MaidTaskScroll.LOGGER.error("Failed to handle maid GUI scroll", e);
        }
    }

    private static MaidGuiInfo getOrCreateGuiInfo(Class<?> screenClass) {
        return CLASS_CACHE.computeIfAbsent(screenClass, MaidGuiInfo::new);
    }

    private static boolean isAbstractMaidContainerGui(Class<?> screenClass) {
        // 先检查缓存
        MaidGuiInfo cached = CLASS_CACHE.get(screenClass);
        if (cached != null) {
            return cached.maidGuiClass != null;
        }

        // 快速字符串检查
        Class<?> clazz = screenClass;
        while (clazz != null && clazz != Object.class) {
            if (clazz.getName().contains(MAID_GUI_CLASS_NAME)) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static boolean isMouseInTaskListArea(Screen screen, double mouseX, double mouseY) {
        if (leftPosField == null || topPosField == null) {
            return false;
        }

        try {
            int leftPos = leftPosField.getInt(screen);
            int topPos = topPosField.getInt(screen);

            int taskListLeft = leftPos - 93;
            int taskListRight = leftPos - 2;
            int taskListTop = topPos + 5;
            int taskListBottom = topPos + 256;

            return mouseX >= taskListLeft && mouseX < taskListRight &&
                    mouseY >= taskListTop && mouseY < taskListBottom;
        } catch (IllegalAccessException e) {
            MaidTaskScroll.LOGGER.error("Failed to get container position", e);
            return false;
        }
    }

    private static void handleTaskListScroll(Screen screen, MaidGuiInfo guiInfo,
                                             double scrollY, ScreenEvent.MouseScrolled.Pre event) {
        try {
            int currentPage = guiInfo.taskPageField.getInt(null);
            int tasksPerPage = guiInfo.taskCountPerPageField.getInt(null);

            MaidTaskScroll.LOGGER.debug("在任务列表区域内滚动！当前页: {}", currentPage);

            int newPage = currentPage;
            boolean shouldUpdate = false;

            if (scrollY > 0 && currentPage > 0) {
                // 向上滚动
                newPage = currentPage - 1;
                shouldUpdate = true;
            } else if (scrollY < 0) {
                // 向下滚动 - 检查是否还有更多任务
                List<IMaidTask> tasks = TaskManager.getTaskIndex();
                if ((currentPage + 1) * tasksPerPage < tasks.size()) {
                    newPage = currentPage + 1;
                    shouldUpdate = true;
                }
            }

            if (shouldUpdate) {
                guiInfo.taskPageField.setInt(null, newPage);

                if (guiInfo.initMethod != null) {
                    guiInfo.initMethod.invoke(screen);
                }

                event.setCanceled(true);
                MaidTaskScroll.LOGGER.debug("任务页面已更新: {} -> {}", currentPage, newPage);
            }

        } catch (Exception e) {
            MaidTaskScroll.LOGGER.error("Failed to update task page", e);
        }
    }

    private static Class<?> findAbstractMaidContainerGuiClass(Class<?> startClass) {
        Class<?> clazz = startClass;
        while (clazz != null && clazz != Object.class) {
            if (clazz.getName().contains(MAID_GUI_CLASS_NAME)) {
                return clazz;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static Method findInitMethod(Class<?> startClass) {
        Class<?> clazz = startClass;
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredMethod("init");
            } catch (NoSuchMethodException e) {
                // 继续查找父类
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}