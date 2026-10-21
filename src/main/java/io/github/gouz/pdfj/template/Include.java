package io.github.gouz.pdfj.template;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.tag.IncludeTag;
import com.hubspot.jinjava.tree.TagNode;
import com.hubspot.jinjava.tree.parse.TagToken;
import com.hubspot.jinjava.tree.parse.TokenScannerSymbols;
import com.hubspot.jinjava.util.HelperStringTokenizer;

import java.util.Objects;

/**
 * {@code {% include %}} of an expression, as in pdfrs: {@code {% include "brands/" ~ brand ~ "/legal.html" %}}.
 * Jinjava's own tag reads only the first word of its argument ({@code "brands/"}); this one resolves the
 * whole argument, then includes the file it names, read by the templates directory's locator like any other.
 */
final class Include extends IncludeTag {
    @Override
    public String interpret(TagNode tagNode, JinjavaInterpreter interpreter) {
        if (new HelperStringTokenizer(tagNode.getHelpers()).allTokens().size() < 2) return super.interpret(tagNode, interpreter);
        String name = Objects.toString(interpreter.resolveELExpression(tagNode.getHelpers().trim(), tagNode.getLineNumber()), "");
        TokenScannerSymbols symbols = interpreter.getConfig().getTokenScannerSymbols();
        String image = symbols.getExpressionStartWithTag() + " include \"" + name + "\" " + symbols.getExpressionEndWithTag();
        TagToken token = new TagToken(image, tagNode.getLineNumber(), tagNode.getStartPosition(), symbols);
        return super.interpret(new TagNode(this, token, symbols), interpreter);
    }
}
