// Pinned SDK SwiftParser/SwiftSyntax census. This tool never evaluates target code.
import Foundation
import SwiftParser
import SwiftSyntax

func range(_ node: some SyntaxProtocol) -> [String:Int] {
    ["start":node.positionAfterSkippingLeadingTrivia.utf8Offset,"end":node.endPositionBeforeTrailingTrivia.utf8Offset]
}
func nameToken(_ node:Syntax) -> TokenSyntax? {
    if let named=node.asProtocol(NamedDeclSyntax.self){return named.name}
    if let parameter=node.as(FunctionParameterSyntax.self){return parameter.secondName ?? parameter.firstName}
    if let pattern=node.as(IdentifierPatternSyntax.self){return pattern.identifier}
    return nil
}
func walk(_ node:Syntax, _ owners:[String], _ output:inout [[String:Any]]) {
    let kind=String(describing:node.kind);let bounds=range(node);var row:[String:Any]=["kind":kind,"start":bounds["start"]!,"end":bounds["end"]!,"owners":owners]
    var nextOwners=owners
    if let token=nameToken(node){row["name"]=token.text;row["nameStart"]=token.positionAfterSkippingLeadingTrivia.utf8Offset;row["nameEnd"]=token.endPositionBeforeTrailingTrivia.utf8Offset
        if node.is(DeclSyntax.self){nextOwners.append(token.text+"@"+String(token.positionAfterSkippingLeadingTrivia.utf8Offset))}
    }
    if let call=node.as(FunctionCallExprSyntax.self){row["callee"]=range(call.calledExpression)}
    if let modifier=node.as(DeclModifierSyntax.self){row["modifier"]=modifier.name.text;var parent=node.parent
        while let current=parent {if current.is(DeclSyntax.self){row["ownerDeclaration"]=range(current);break};parent=current.parent}
    }
    if let ext=node.as(ExtensionDeclSyntax.self){row["name"]="<extension>";row["nameStart"]=ext.extendedType.positionAfterSkippingLeadingTrivia.utf8Offset;row["nameEnd"]=ext.extendedType.endPositionBeforeTrailingTrivia.utf8Offset;row["conditionalExtension"]=ext.genericWhereClause != nil;nextOwners.append("<extension>@"+String(ext.extendedType.positionAfterSkippingLeadingTrivia.utf8Offset))}
    if let parent=node.parent {row["parentKind"]=String(describing:parent.kind)}
    var ancestor=node.parent
    while let current=ancestor {if let ext=current.as(ExtensionDeclSyntax.self),ext.genericWhereClause != nil {row["conditionalExtension"]=true;break};ancestor=current.parent}
    if node.is(DeclSyntax.self){row["declaration"]=true}
    if node.is(TypeSyntax.self){row["typeUse"]=true}
    output.append(row)
    for child in node.children(viewMode:.sourceAccurate){walk(child,nextOwners,&output)}
}
let input=try Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))
guard let text=String(data:input,encoding:.utf8) else {fatalError("SWIFT_UTF8_REQUIRED")}
let tree=Parser.parse(source:text);var nodes:[[String:Any]]=[];walk(Syntax(tree),[],&nodes)
let encoded=try JSONSerialization.data(withJSONObject:["schema":"native-swift-census:m8.4-v1","hasError":tree.hasError,"nodes":nodes],options:[.sortedKeys,.withoutEscapingSlashes])
FileHandle.standardOutput.write(encoded);FileHandle.standardOutput.write(Data([10]))
