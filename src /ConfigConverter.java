import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ConfigConverter {
    // Хранилище для констант (ключ -> значение)
    private static final Map<String, Object> constants = new HashMap<>();
    // Результирующая структура для TOML (LinkedHashMap сохраняет порядок вставки)
    private static final Map<String, Object> tomlMap = new LinkedHashMap<>();

    private static String inputPath; // Путь к входному файлу
    private static int lineNum = 0;  // Номер текущей обрабатываемой строки (для сообщений об ошибках)
    private static String currentLine; // Текст текущей строки

    // Регулярные выражения для синтаксического анализа
    private static final Pattern SINGLE_LINE_COMMENT = Pattern.compile("^;"); // Однострочные комментарии
    private static final Pattern MULTILINE_COMMENT_START = Pattern.compile("^--\\[\\["); // Начало многострочного комментария
    private static final Pattern MULTILINE_COMMENT_END = Pattern.compile("\\]\\]$"); // Конец многострочного комментария
    private static final Pattern HEX_NUMBER = Pattern.compile("^0[xX][0-9a-fA-F]+$"); // Шестнадцатеричные числа
    private static final Pattern DECIMAL_NUMBER = Pattern.compile("^\\d+$"); // Десятичные целые числа
    private static final Pattern ARRAY_START = Pattern.compile("^#\\("); // Начало массива
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z][_a-zA-Z0-9]*$"); // Идентификаторы (имена констант)
    private static final Pattern STRING = Pattern.compile("^\"([^\"]*)\"$"); // Строки в кавычках
    private static final Pattern CONSTANT_DECL = Pattern.compile("^var\\s+([a-zA-Z][_a-zA-Z0-9]*)\\s*=\\s*(.+)$"); // Объявление константы
    private static final Pattern CONSTANT_USE = Pattern.compile("!\\{([a-zA-Z][_a-zA-Z0-9]*)\\}"); // Использование константы
    private static final Pattern KEY_VALUE = Pattern.compile("^([a-zA-Z][_a-zA-Z0-9]*)\\s*=\\s*(.+)$"); // Пара ключ-значение

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("=== Конвертер конфигурационного языка в TOML ===");
            System.out.print("Введите путь к файлу: ");
            inputPath = scanner.nextLine().trim();

            // Проверка существования файла
            File inputFile = new File(inputPath);
            if (!inputFile.exists() || !inputFile.isFile()) {
                System.err.println("Ошибка: Файл '" + inputPath + "' не найден!");
                System.exit(1);
            }

            System.out.println("\nОбработка файла: " + inputPath);
            parseInput(); // Основной парсинг файла
            String tomlResult = convertToTomlString(); // Преобразование в TOML-строку

            // Вывод результата
            System.out.println("\n" + "=".repeat(50));
            System.out.println("Результат в формате TOML:");
            System.out.println("=".repeat(50));
            System.out.println(tomlResult);
            System.out.println("=".repeat(50));

        } catch (Exception e) {
            System.err.println("\nОшибка при обработке файла!");
            System.err.println("Сообщение: " + e.getMessage());
            if (lineNum > 0) {
                System.err.println("Строка: " + lineNum);
                System.err.println("Контекст: " + currentLine);
            }
            System.exit(1);
        }
    }

    /**
     * Основной метод парсинга входного файла
     * Удаляет комментарии и пустые строки, затем передает на обработку
     */
    private static void parseInput() throws IOException {
        BufferedReader fileReader = new BufferedReader(new FileReader(inputPath));
        StringBuilder processedInput = new StringBuilder();
        boolean inMultilineComment = false;
        lineNum = 0;

        // Первый проход: удаление комментариев и пустых строк
        while ((currentLine = fileReader.readLine()) != null) {
            lineNum++;
            String trimmed = currentLine.trim();

            // Пропускаем пустые строки
            if (trimmed.isEmpty()) {
                continue;
            }

            // Обработка многострочных комментариев
            if (inMultilineComment) {
                if (MULTILINE_COMMENT_END.matcher(trimmed).find()) {
                    inMultilineComment = false;
                }
                continue;
            }

            if (MULTILINE_COMMENT_START.matcher(trimmed).find()) {
                inMultilineComment = true;
                continue;
            }

            // Пропускаем однострочные комментарии
            if (SINGLE_LINE_COMMENT.matcher(trimmed).find()) {
                continue;
            }

            processedInput.append(trimmed).append("\n");
        }
        fileReader.close();

        // Второй проход: парсинг "очищенного" текста
        String[] lines = processedInput.toString().split("\n");
        lineNum = 0; // Сбрасываем счетчик для нумерации очищенных строк

        for (String line : lines) {
            lineNum++;
            currentLine = line;

            if (line.trim().isEmpty()) {
                continue;
            }

            parseLine(line.trim()); // Парсинг каждой строки
        }
    }

    /**
     * Парсинг отдельной строки
     * Определяет тип конструкции и обрабатывает соответствующим образом
     */
    private static void parseLine(String line) {
        // Проверка на объявление константы (var name = value)
        Matcher constMatcher = CONSTANT_DECL.matcher(line);
        if (constMatcher.matches()) {
            String name = constMatcher.group(1);
            String valueExpr = constMatcher.group(2);
            Object value = parseValue(valueExpr);
            constants.put(name, value);
            return;
        }

        // Проверка на пару ключ-значение (key = value)
        Matcher kvMatcher = KEY_VALUE.matcher(line);
        if (kvMatcher.matches()) {
            String key = kvMatcher.group(1);
            String valueExpr = kvMatcher.group(2);
            Object value = parseValue(valueExpr);
            tomlMap.put(key, value);
            return;
        }

        // Если строка не соответствует ни одному шаблону - ошибка
        throw new RuntimeException("Синтаксическая ошибка в строке " + lineNum + ": \"" + line + "\"");
    }

    /**
     * Парсинг значения выражения
     * Поддерживает: константы, строки, массивы, числа (hex/dec), булевы значения
     */
    private static Object parseValue(String expr) {
        expr = expr.trim();

        // Проверка на использование константы (!{CONST_NAME})
        Matcher constUseMatcher = CONSTANT_USE.matcher(expr);
        if (constUseMatcher.matches()) {
            String constName = constUseMatcher.group(1);
            if (!constants.containsKey(constName)) {
                throw new RuntimeException("Неопределенная константа: " + constName + " в строке " + lineNum);
            }
            return constants.get(constName); // Возвращаем значение ранее объявленной константы
        }

        // Проверка на строку в кавычках
        Matcher stringMatcher = STRING.matcher(expr);
        if (stringMatcher.matches()) {
            return stringMatcher.group(1); // Возвращаем содержимое без кавычек
        }

        // Проверка на массив (#(element1, element2, ...))
        if (ARRAY_START.matcher(expr).find()) {
            if (!expr.endsWith(")")) {
                throw new RuntimeException("Ошибка синтаксиса массива в строке " + lineNum + ": отсутствует закрывающая скобка");
            }
            String arrayContent = expr.substring(2, expr.length() - 1).trim(); // Убираем #( и )
            if (arrayContent.isEmpty()) {
                return new ArrayList<>(); // Пустой массив
            }
            String[] elements = arrayContent.split(",");
            List<Object> array = new ArrayList<>();
            for (String element : elements) {
                array.add(parseValue(element.trim())); // Рекурсивный парсинг элементов
            }
            return array;
        }

        // Проверка на шестнадцатеричное число (0x...)
        if (HEX_NUMBER.matcher(expr).matches()) {
            return Integer.parseInt(expr.substring(2), 16); // Конвертация из hex
        }

        // Проверка на десятичное целое число
        if (DECIMAL_NUMBER.matcher(expr).matches()) {
            return Integer.parseInt(expr);
        }

        // Проверка на идентификатор (должен быть объявленной константой)
        if (IDENTIFIER.matcher(expr).matches()) {
            if (!constants.containsKey(expr)) {
                throw new RuntimeException("Неопределенная константа или неверное значение: \"" + expr + "\" в строке " + lineNum);
            }
            return constants.get(expr);
        }

        // Проверка на булево значение
        if (expr.equals("true") || expr.equals("false")) {
            return Boolean.parseBoolean(expr);
        }

        // Если выражение не соответствует ни одному типу - ошибка
        throw new RuntimeException("Неверное выражение значения в строке " + lineNum + ": \"" + expr + "\"");
    }

    /**
     * Конвертация внутренней структуры в TOML-строку
     */
    private static String convertToTomlString() {
        StringBuilder result = new StringBuilder();

        for (Map.Entry<String, Object> entry : tomlMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            result.append(key).append(" = ").append(formatTomlValue(value)).append("\n");
        }

        return result.toString();
    }

    /**
     * Форматирование значения в TOML-синтаксис
     */
    private static String formatTomlValue(Object value) {
        if (value instanceof String) {
            return "\"" + escapeString((String) value) + "\""; // Строки в кавычках
        } else if (value instanceof Integer) {
            return value.toString(); // Целые числа без изменений
        } else if (value instanceof Boolean) {
            return value.toString(); // Булевы значения (true/false)
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                return "[]"; // Пустой массив
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                sb.append(formatTomlValue(list.get(i))); // Рекурсивное форматирование элементов
                if (i < list.size() - 1) {
                    sb.append(", "); // Разделитель между элементами
                }
            }
            sb.append("]");
            return sb.toString();
        }
        throw new RuntimeException("Неподдерживаемый тип значения: " + value.getClass());
    }

    /**
     * Экранирование специальных символов в строках для TOML
     */
    private static String escapeString(String str) {
        return str.replace("\\", "\\\\")  // Обратный слэш
                .replace("\"", "\\\"")    // Кавычки
                .replace("\n", "\\n")     // Перенос строки
                .replace("\t", "\\t");    // Табуляция
    }
}
