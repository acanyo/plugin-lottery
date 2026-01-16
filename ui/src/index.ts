import { definePlugin } from "@halo-dev/ui-shared";
import { markRaw } from "vue";
import LotteryList from "./views/LotteryList.vue";
import MyWinnings from "./views/MyWinnings.vue";
import LotteryCard from "./editor/index";
import RiGiftLine from "~icons/ri/gift-line";

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "ToolsRoot",
      route: {
        path: "/lottery",
        name: "LotteryList",
        component: LotteryList,
        meta: {
          title: "抽奖管理",
          description: "创建和管理抽奖活动，支持多种参与条件和自动开奖",
          searchable: true,
          permissions: ["plugin:lottery:view"],
          menu: {
            name: "抽奖管理",
            icon: markRaw(RiGiftLine),
            priority: 0,
          },
        },
      },
    },
  ],
  ucRoutes: [
    {
      parentName: "Root",
      route: {
        path: "/lottery/winnings",
        name: "MyWinnings",
        component: MyWinnings,
        meta: {
          title: "我的中奖记录",
          description: "查看我的抽奖中奖记录",
          searchable: true,
          menu: {
            name: "中奖记录",
            icon: markRaw(RiGiftLine),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {
    "default:editor:extension:create": () => {
      return [LotteryCard];
    },
  },
});
