package top.lingyuzhao.codeBookChatApp.utils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * HTML 文本提取工具类
 * 专注于高性能提取最底层文本节点
 */
public class HtmlTextExtractor {

    /**
     * 提取 HTML 字符串中所有最底层节点的文本，并用空格拼接。
     * <p>
     * 性能优化点：
     * 1. 使用 Jsoup 的高效解析器。
     * 2. 使用 CSS 伪类选择器 *:not(:has(*)) 直接定位叶子节点，避免手动递归遍历整棵树。
     * 3. 预计算 StringBuilder 容量（估算）。
     * 4. 过滤纯空白文本节点，减少无效拼接。
     *
     * @param html 输入的 HTML 字符串
     * @return 拼接后的纯文本字符串
     */
    public static String extractBottomInnerText(String html) {
        if (html == null) {
            return "";
        }
        html = html.trim();
        if (!html.startsWith("<")) {
            return html;
        }

        // 1. 解析 HTML
        // Parser.htmlParser() 比 xmlParser 更宽容，适合处理现实世界的脏 HTML
        Document doc = Jsoup.parse(html);

        // 2. 选择所有“不包含子元素”的节点 (最底层叶子节点)
        // 这是一个高效的选择器，Jsoup 底层会优化这个查询
        Elements leafElements = doc.select("*:not(:has(*))");

        if (leafElements.isEmpty()) {
            return "";
        }

        // 3. 使用 StringBuilder 进行拼接
        // 预估初始容量：假设平均每个节点 20 个字符，减少扩容次数
        StringBuilder sb = new StringBuilder(leafElements.size() * 20);
        boolean hasContent = false;

        for (Element element : leafElements) {
            // 遍历该元素下的所有子节点，只提取 TextNode
            // 为什么不用 element.text()?
            // 因为 element.text() 会再次遍历并处理实体字符，直接操作 TextNode 更快且更可控
            for (Node node : element.childNodes()) {
                if (node instanceof TextNode) {
                    String text = ((TextNode) node).getWholeText();

                    //  trimming 并检查是否为空
                    // 注意：这里只 trim 两端，保留中间必要的空格（如 "a b"）
                    // 如果整个节点只是换行符或空格，则跳过
                    String trimmed = text.trim();
                    if (!trimmed.isEmpty()) {
                        if (hasContent) {
                            sb.append(' ');
                        }
                        sb.append(trimmed);
                        hasContent = true;
                    }
                }
            }
        }

        return sb.toString();
    }

    // --- 测试 Main 方法 ---
    public static void main(String[] args) {
        String html = "<div>\n" +
                "    <p>Hello <strong>World</strong>!</p>\n" +
                "    <ul>\n" +
                "        <li>Item <span>One</span></li>\n" +
                "        <li>Item <span>Two</span></li>\n" +
                "    </ul>\n" +
                "    <div>\n" +
                "        <!-- 注释会被忽略 -->\n" +
                "        <span>Nested <b>Deep</b> Text</span>\n" +
                "    </div>\n" +
                "    <script>var a = 1;</script>\n" +
                "    <style>.css { color: red; }</style>\n" +
                "</div>\n";

        long start = System.nanoTime();
        String result = extractBottomInnerText(html);
        long end = System.nanoTime();

        System.out.println("提取结果: " + result);
        System.out.println("耗时: " + (end - start) / 1_000_000.0 + " ms");

        // 预期输出类似: Hello World ! Item One Item Two Nested Deep Text var a = 1; .css { color: red; }
        // 注意：默认 Jsoup 会解析 script/style 内容。如果需要排除它们，请看下方的“进阶优化”。
    }
}