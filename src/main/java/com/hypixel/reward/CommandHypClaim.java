package com.hypixel.reward;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.EnumChatFormatting;

public class CommandHypClaim extends CommandBase {

    @Override
    public String getCommandName() {
        return "hypclaim";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/hypclaim select <序号> | confirm | reselect | cancel";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            HypRewardEventHandler.sendChat(EnumChatFormatting.YELLOW + "用法: /hypclaim select <序号> | confirm | reselect | cancel");
            return;
        }

        if (!HypRewardEventHandler.hasActiveSession()) {
            HypRewardEventHandler.sendChat(EnumChatFormatting.RED + "[HypReward] 当前没有待领取的奖励，等待聊天栏中的奖励链接...");
            return;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "select":
                if (args.length < 2) {
                    HypRewardEventHandler.sendChat(EnumChatFormatting.RED + "用法: /hypclaim select <序号>");
                    return;
                }
                int index;
                try {
                    index = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    HypRewardEventHandler.sendChat(EnumChatFormatting.RED + "请输入有效的数字");
                    return;
                }
                if (index < 0 || index >= HypRewardEventHandler.claimOptions.size()) {
                    HypRewardEventHandler.sendChat(EnumChatFormatting.RED + "序号超出范围 (0~" +
                            (HypRewardEventHandler.claimOptions.size() - 1) + ")");
                    return;
                }
                HypRewardEventHandler.claimSelectedIndex = index;
                HypRewardEventHandler.showConfirmation(index);
                break;

            case "confirm":
                if (HypRewardEventHandler.claimSelectedIndex < 0) {
                    HypRewardEventHandler.sendChat(EnumChatFormatting.RED + "[HypReward] 请先选择一个奖励");
                    return;
                }
                HypRewardEventHandler.claimReward();
                break;

            case "reselect":
                HypRewardEventHandler.claimSelectedIndex = -1;
                HypRewardEventHandler.sendChat(EnumChatFormatting.YELLOW + "[HypReward] 请重新选择奖励:");
                // Re-show options
                HypRewardEventHandler.showRewardOptions(HypRewardEventHandler.claimOptions);
                break;

            case "cancel":
                HypRewardEventHandler.clearSession();
                HypRewardEventHandler.sendChat(EnumChatFormatting.RED + "[HypReward] 已取消领取");
                break;

            default:
                HypRewardEventHandler.sendChat(EnumChatFormatting.RED + "未知子命令: " + sub);
                HypRewardEventHandler.sendChat(EnumChatFormatting.YELLOW + "用法: /hypclaim select <序号> | confirm | reselect | cancel");
                break;
        }
    }
}
