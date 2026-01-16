import {
  Node,
  VueNodeViewRenderer,
  mergeAttributes,
  isActive,
  type Editor,
  type Range,
  type EditorState,
} from "@halo-dev/richtext-editor";
import { markRaw } from "vue";
import LotteryCardView from "./LotteryCardView.vue";
import LotteryToolboxItem from "./LotteryToolboxItem.vue";
import RiGiftLine from "~icons/ri/gift-line";
import MdiDeleteForeverOutline from "~icons/mdi/delete-forever-outline";
import { deleteNode } from "../utils/delete-node";

declare module "@halo-dev/richtext-editor" {
  interface Commands<ReturnType> {
    lotteryCard: {
      setLotteryCard: (options: { name: string }) => ReturnType;
    };
  }
}

// 从 URL 获取当前编辑的内容信息
function getCurrentEditorContent(): { type: 'post' | 'page'; name: string } | null {
  const url = new URL(window.location.href);
  const name = url.searchParams.get('name');
  const pathname = url.pathname;
  
  if (!name) return null;
  
  if (pathname.includes('/posts/editor')) {
    return { type: 'post', name };
  } else if (pathname.includes('/single-pages/editor')) {
    return { type: 'page', name };
  }
  return null;
}

const LotteryCard = Node.create({
  name: "lottery-card",
  group: "block",
  atom: true,
  draggable: true,

  addAttributes() {
    return {
      name: {
        default: "",
        parseHTML: (element: HTMLElement) => {
          return element.getAttribute("name") || "";
        },
        renderHTML: (attributes: { name?: string }) => {
          if (!attributes.name) {
            return {};
          }
          return {
            name: attributes.name,
          };
        },
      },
      // 当前内容的 name（文章或页面）
      "content-name": {
        default: "",
        parseHTML: (element: HTMLElement) => {
          return element.getAttribute("content-name") || "";
        },
        renderHTML: (attributes: { "content-name"?: string }) => {
          if (!attributes["content-name"]) {
            return {};
          }
          return {
            "content-name": attributes["content-name"],
          };
        },
      },
      // 当前内容的类型：post 或 page
      "content-type": {
        default: "",
        parseHTML: (element: HTMLElement) => {
          return element.getAttribute("content-type") || "";
        },
        renderHTML: (attributes: { "content-type"?: string }) => {
          if (!attributes["content-type"]) {
            return {};
          }
          return {
            "content-type": attributes["content-type"],
          };
        },
      },
    };
  },

  parseHTML() {
    return [{ tag: "lottery-card" }];
  },

  renderHTML({ HTMLAttributes }: { HTMLAttributes: Record<string, unknown> }) {
    return ["lottery-card", mergeAttributes(HTMLAttributes)];
  },

  addNodeView() {
    return VueNodeViewRenderer(LotteryCardView as never);
  },

  addCommands() {
    return {
      setLotteryCard:
        (options: { name: string }) =>
        ({ commands }) => {
          // 自动获取当前编辑内容的信息
          const content = getCurrentEditorContent();
          return commands.insertContent({
            type: this.name,
            attrs: {
              ...options,
              "content-name": content?.name || "",
              "content-type": content?.type || "",
            },
          });
        },
    };
  },

  addOptions() {
    return {
      ...this.parent?.(),
      getToolboxItems({ editor }: { editor: Editor }) {
        return {
          priority: 60,
          component: markRaw(LotteryToolboxItem),
          props: {
            editor,
          },
        };
      },
      getCommandMenuItems() {
        return {
          priority: 200,
          icon: markRaw(RiGiftLine),
          title: "抽奖",
          keywords: ["lottery", "choujiang", "抽奖"],
          command: ({ editor, range }: { editor: Editor; range: Range }) => {
            editor.chain().focus().deleteRange(range).setLotteryCard({ name: "" }).run();
          },
        };
      },
      getBubbleMenu({ editor }: { editor: Editor }) {
        return {
          pluginKey: "lottery-card-bubble-menu",
          shouldShow: ({ state }: { state: EditorState }) => {
            return isActive(state, "lottery-card");
          },
          items: [
            {
              priority: 10,
              props: {
                icon: markRaw(MdiDeleteForeverOutline),
                title: "删除",
                action: () => {
                  deleteNode("lottery-card", editor);
                },
              },
            },
          ],
        };
      },
    };
  },
});

export default LotteryCard;
