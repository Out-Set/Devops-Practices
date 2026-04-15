import java.util.Properties;

class Test {

    public static void printSystemPrpoerties(){
        System.out.println("Printing System Properties: ");
        Properties properties = System.getProperties();
        System.out.println(properties);
    }
    
    public static void main(String[] args) {
        
        System.out.println("Program Started...");
        printSystemPrpoerties();
    }
}