import Swift
class Base {}
class Derived:Base { var counter:Int=0; @available(*, deprecated) func twice(_ n:Int)->Int {n*2}; func run()->Int {counter+=1; if counter>0 {return twice(4)}; return 0} }
