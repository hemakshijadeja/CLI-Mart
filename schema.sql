-- DDL Commands for Shopping Cart App
CREATE DATABASE IF NOT EXISTS shoppingCartDb;
USE shoppingCartDb;

-- 1. Users Table
CREATE TABLE users (
    userId INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL
);

-- 2. Wallet Table (1-to-1 relationship with Users)
CREATE TABLE wallet (
    userId INT PRIMARY KEY,
    balance DECIMAL(10,2) DEFAULT 0.00,
    FOREIGN KEY (userId) REFERENCES users(userId)
);

-- 3. Product Inventory Table
CREATE TABLE product (
    productId INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    stockQty INT NOT NULL
);

-- 4. Cart Table
CREATE TABLE cart (
    cartId INT PRIMARY KEY AUTO_INCREMENT,
    userId INT NOT NULL,
    status ENUM('ACTIVE', 'CHECKED_OUT') DEFAULT 'ACTIVE',
    FOREIGN KEY (userId) REFERENCES users(userId)
);

-- 5. Cart Items Table
CREATE TABLE cartItem (
    cartId INT NOT NULL,
    productId INT NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (cartId, productId),
    FOREIGN KEY (cartId) REFERENCES cart(cartId),
    FOREIGN KEY (productId) REFERENCES product(productId)
);

-- 6. Bill/Invoice Table
CREATE TABLE bill (
    billId INT PRIMARY KEY AUTO_INCREMENT,
    cartId INT NOT NULL,
    userId INT NOT NULL,
    totalAmount DECIMAL(10,2) NOT NULL,
    billedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (cartId) REFERENCES cart(cartId),
    FOREIGN KEY (userId) REFERENCES users(userId)
);

-- 7. Bill Items Table
CREATE TABLE billItem (
    billId INT NOT NULL,
    productId INT NOT NULL,
    productName VARCHAR(100) NOT NULL,
    quantity INT NOT NULL,
    unitPrice DECIMAL(10,2) NOT NULL,
    lineTotal DECIMAL(10,2) AS (quantity * unitPrice),
    PRIMARY KEY (billId, productId),
    FOREIGN KEY (billId) REFERENCES bill(billId),
    FOREIGN KEY (productId) REFERENCES product(productId)
);

-- Insert sample users
INSERT INTO users (name) VALUES 
('Hemakshi Jadeja'),
('Hemy'),
('Shailvi');

INSERT INTO wallet (userId, balance) VALUES 
(1, 5000.00),
(2, 1500.50),
(3, 0.00);

-- Insert sample products in the inventory
INSERT INTO product (name, price, stockQty) VALUES 
('Plushie', 499.99, 50),
('Shoes', 2999.00, 20),
('Airpods', 15000.00, 10),
('Sunscreen', 299.00, 100),
('Headphones', 1999.50, 30);
